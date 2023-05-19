/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.eventmesh.connector;

import org.apache.eventmesh.client.tcp.EventMeshTCPClient;
import org.apache.eventmesh.client.tcp.EventMeshTCPClientFactory;
import org.apache.eventmesh.client.tcp.common.MessageUtils;
import org.apache.eventmesh.client.tcp.common.ReceiveMsgHook;
import org.apache.eventmesh.client.tcp.conf.EventMeshTCPClientConfig;
import org.apache.eventmesh.common.protocol.SubscriptionMode;
import org.apache.eventmesh.common.protocol.SubscriptionType;
import org.apache.eventmesh.common.protocol.tcp.UserAgent;
import org.apache.eventmesh.common.utils.SystemUtils;
import org.apache.eventmesh.connector.api.config.SinkConfig;
import org.apache.eventmesh.connector.api.data.ConnectRecord;
import org.apache.eventmesh.connector.api.sink.Sink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.cloudevents.CloudEvent;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SinkWorker implements ConnectorWorker {

    private final Sink sink;
    private final SinkConfig config;

    private final EventMeshTCPClient<CloudEvent> eventMeshTCPClient;

    public SinkWorker(Sink sink, SinkConfig config) throws Exception {
        this.sink = sink;
        this.config = config;
        sink.init(config);
        eventMeshTCPClient = buildEventMeshSubClient(config);
        eventMeshTCPClient.init();
    }

    private EventMeshTCPClient<CloudEvent> buildEventMeshSubClient(SinkConfig config) {
        String meshAddress = config.getPubSubConfig().getMeshAddress();
        String meshIp = meshAddress.split(":")[0];
        int meshPort = Integer.parseInt(meshAddress.split(":")[1]);
        UserAgent agent = UserAgent.builder()
            .env(config.getPubSubConfig().getEnv())
            .host("localhost")
            .password(config.getPubSubConfig().getPassWord())
            .username(config.getPubSubConfig().getUserName())
            .group(config.getPubSubConfig().getGroup())
            .path("/")
            .port(8362)
            .subsystem(config.getPubSubConfig().getSubsystem())
            .pid(Integer.parseInt(SystemUtils.getProcessId()))
            .version("2.0")
            .idc(config.getPubSubConfig().getIdc())
            .build();
        UserAgent userAgent = MessageUtils.generateSubClient(agent);

        EventMeshTCPClientConfig eventMeshTcpClientConfig = EventMeshTCPClientConfig.builder()
            .host(meshIp)
            .port(meshPort)
            .userAgent(userAgent)
            .build();
        return EventMeshTCPClientFactory.createEventMeshTCPClient(eventMeshTcpClientConfig, CloudEvent.class);
    }

    @Override
    public void start() {
        log.info("sink worker starting {}", sink.name());
        log.info("event mesh address is {}", config.getPubSubConfig().getMeshAddress());
        try {
            sink.start();
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
        eventMeshTCPClient.subscribe(config.getPubSubConfig().getMeshTopic(), SubscriptionMode.CLUSTERING,
            SubscriptionType.ASYNC);
        eventMeshTCPClient.registerSubBusiHandler(new EventHandler(sink));
        eventMeshTCPClient.listen();
    }

    @Override
    public void stop() {
        log.info("sink worker stopping");
        try {
            eventMeshTCPClient.unsubscribe();
            eventMeshTCPClient.close();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("event mesh client close", e);
        }
        try {
            sink.stop();
        } catch (Exception e) {
            log.error("sink destroy error", e);
        }

        log.info("source worker stopped");
    }

    static class EventHandler implements ReceiveMsgHook<CloudEvent> {
        private final Sink sink;

        public EventHandler(Sink sink) {
            this.sink = sink;
        }

        @Override
        public Optional<CloudEvent> handle(CloudEvent event) {
            byte[] body = Objects.requireNonNull(event.getData()).toBytes();
            //todo: recordPartition & recordOffset
            ConnectRecord connectRecord = new ConnectRecord(null, null, System.currentTimeMillis(), body);
            for (String extensionName : event.getExtensionNames()) {
                connectRecord.addExtension(extensionName, Objects.requireNonNull(event.getExtension(extensionName)).toString());
            }
            connectRecord.addExtension("id", event.getId());
            connectRecord.addExtension("topic", event.getSubject());
            connectRecord.addExtension("source", event.getSource().toString());
            connectRecord.addExtension("type", event.getType());
            List<ConnectRecord> connectRecords = new ArrayList<>();
            connectRecords.add(connectRecord);
            sink.put(connectRecords);
            return Optional.empty();
        }
    }
}