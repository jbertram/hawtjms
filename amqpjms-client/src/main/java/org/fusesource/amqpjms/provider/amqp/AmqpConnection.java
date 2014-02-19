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

import java.util.HashMap;
import java.util.Map;

import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Session;
import org.fusesource.amqpjms.jms.meta.JmsSessionId;
import org.fusesource.amqpjms.jms.meta.JmsSessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AmqpConnection {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpConnection.class);

    private final Connection connection;
    private final Map<JmsSessionId, AmqpSession> sessions = new HashMap<JmsSessionId, AmqpSession>();
    private final Map<JmsSessionId, Session> pendingSessions = new HashMap<JmsSessionId, Session>();

    public AmqpConnection(Connection connection) {
        this.connection = connection;
    }

    public void close() {
        this.connection.close();
    }

    public Connection getProtonConnection() {
        return this.connection;
    }

    public Session createSession(JmsSessionInfo sessionInfo) {
        Session session = this.connection.session();
        return session;
    }
}
