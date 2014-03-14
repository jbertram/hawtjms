/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.amqpjms.provider.amqp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.qpid.proton.engine.Session;
import org.fusesource.amqpjms.jms.JmsDestination;
import org.fusesource.amqpjms.jms.meta.JmsConsumerId;
import org.fusesource.amqpjms.jms.meta.JmsConsumerInfo;
import org.fusesource.amqpjms.jms.meta.JmsProducerId;
import org.fusesource.amqpjms.jms.meta.JmsProducerInfo;
import org.fusesource.amqpjms.jms.meta.JmsSessionId;
import org.fusesource.amqpjms.jms.meta.JmsSessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AmqpSession extends AbstractAmqpResource<JmsSessionInfo, Session> {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpSession.class);

    private final AmqpConnection connection;

    private final Map<JmsConsumerId, AmqpConsumer> consumers = new HashMap<JmsConsumerId, AmqpConsumer>();
    private final Map<JmsProducerId, AmqpProducer> producers = new HashMap<JmsProducerId, AmqpProducer>();

    private final ArrayList<AmqpLink> pendingOpenLinks = new ArrayList<AmqpLink>();
    private final ArrayList<AmqpLink> pendingCloseLinks = new ArrayList<AmqpLink>();

    public AmqpSession(AmqpConnection connection, JmsSessionInfo info) {
        super(info, connection.getProtonConnection().session());
        this.connection = connection;

        this.info.getSessionId().setProviderHint(this);
    }

    @Override
    protected void doOpen() {
        this.connection.addToPendingOpen(this);
    }

    @Override
    protected void doClose() {
        this.connection.addToPendingClose(this);
    }

    /**
     * Perform an acknowledge of all delivered messages for all consumers active in this
     * Session.
     */
    public void acknowledge() {
        for (AmqpConsumer consumer : consumers.values()) {
            consumer.acknowledge();
        }
    }

    public AmqpProducer createProducer(JmsProducerInfo producerInfo) {
        if (producerInfo.getDestination() != null) {
            LOG.debug("Creating fixed Producer for: {}", producerInfo.getDestination());
            return new AmqpFixedProducer(this, producerInfo);
        } else {
            LOG.debug("Creating an Anonymous Producer: ");
            return new AmqpAnonymousProducer(this, producerInfo);
        }
    }

    public AmqpProducer getProducer(JmsProducerInfo producerInfo) {
        return getProducer(producerInfo.getProducerId());
    }

    public AmqpProducer getProducer(JmsProducerId producerId) {
        if (producerId.getProviderHint() instanceof AmqpProducer) {
            return (AmqpProducer) producerId.getProviderHint();
        }
        return this.producers.get(producerId);
    }

    public AmqpConsumer createConsumer(JmsConsumerInfo consumerInfo) {
        return new AmqpConsumer(this, consumerInfo);
    }

    public AmqpConsumer getConsumer(JmsConsumerInfo consumerInfo) {
        return getConsumer(consumerInfo.getConsumerId());
    }

    public AmqpConsumer getConsumer(JmsConsumerId consumerId) {
        if (consumerId.getProviderHint() instanceof AmqpConsumer) {
            return (AmqpConsumer) consumerId.getProviderHint();
        }
        return this.consumers.get(consumerId);
    }

    /**
     * Called from the parent Connection to check for and react to state changes in the
     * underlying Proton connection which might indicate a sender / receiver / link state
     * has changed.
     */
    @Override
    public void processUpdates() {
        processPendingLinks();

        // Settle any pending deliveries.
        for (AmqpProducer producer : this.producers.values()) {
            producer.processUpdates();
        }

        for (AmqpConsumer consumer : this.consumers.values()) {
            consumer.processUpdates();
        }
    }

    private void processPendingLinks() {

        if (pendingOpenLinks.isEmpty() && pendingCloseLinks.isEmpty()) {
            return;
        }

        Iterator<AmqpLink> linkIterator = pendingOpenLinks.iterator();
        while (linkIterator.hasNext()) {
            AmqpLink candidate = linkIterator.next();
            LOG.trace("Checking Link {} for open state: {}", candidate, candidate.isOpen());
            if (candidate.isOpen()) {
                if (candidate instanceof AmqpConsumer) {
                    AmqpConsumer consumer = (AmqpConsumer) candidate;
                    consumers.put(consumer.getConsumerId(), consumer);
                } else {
                    AmqpProducer producer = (AmqpProducer) candidate;
                    producers.put(producer.getProducerId(), producer);
                }

                LOG.debug("Link {} is now open: ", candidate);
                candidate.opened();
            } else if (candidate.isClosed()) {
                LOG.warn("Open of link {} failed: ", candidate);
                Exception remoteError = candidate.getRemoteError();
                candidate.failed(remoteError);
            } else {
                // Don't remove, it's still pending.
                continue;
            }

            linkIterator.remove();
        }

        linkIterator = pendingCloseLinks.iterator();
        while (linkIterator.hasNext()) {
            AmqpLink candidate = linkIterator.next();
            if (candidate.isClosed()) {
                candidate.closed();
                linkIterator.remove();
            }
        }
    }

    /**
     * Adds Topic or Queue qualifiers to the destination target.  We don't add qualifiers to
     * Temporary Topics and Queues since AMQP works a bit differently.
     *
     * @param destination
     *        The destination to Qualify.
     *
     * @return the qualified destination name.
     */
    public String getQualifiedName(JmsDestination destination) {
        String result = destination.getName();

        if (!destination.isTemporary()) {
            if (destination.isTopic()) {
                result = connection.getTopicPrefix() + destination.getName();
            } else {
                result = connection.getQueuePrefix() + destination.getName();
            }
        }

        return result;
    }

    public AmqpProvider getProvider() {
        return this.connection.getProvider();
    }

    public AmqpConnection getConnection() {
        return this.connection;
    }

    public JmsSessionId getSessionId() {
        return this.info.getSessionId();
    }

    public Session getProtonSession() {
        return this.endpoint;
    }

    void addPedingLinkOpen(AmqpLink link) {
        this.pendingOpenLinks.add(link);
    }

    void addPedingLinkClose(AmqpLink link) {
        this.pendingCloseLinks.add(link);
    }
}
