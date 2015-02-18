/*
 * Copyright 2014 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kaaproject.kaa.client.logging;

import static org.kaaproject.kaa.client.logging.DefaultLogUploadConfiguration.Builder.BATCH_VOLUME;
import static org.kaaproject.kaa.client.logging.DefaultLogUploadConfiguration.Builder.MAX_STORAGE_SIZE;
import static org.kaaproject.kaa.client.logging.DefaultLogUploadConfiguration.Builder.SINK_THRESHOLD;
import static org.kaaproject.kaa.client.logging.DefaultLogUploadConfiguration.Builder.UPLOAD_TIMEOUT;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.kaaproject.kaa.client.channel.KaaChannelManager;
import org.kaaproject.kaa.client.channel.LogTransport;
import org.kaaproject.kaa.client.logging.gen.SuperRecord;
import org.kaaproject.kaa.common.endpoint.gen.LogDeliveryStatus;
import org.kaaproject.kaa.common.endpoint.gen.LogEntry;
import org.kaaproject.kaa.common.endpoint.gen.LogSyncRequest;
import org.kaaproject.kaa.common.endpoint.gen.LogSyncResponse;
import org.kaaproject.kaa.common.endpoint.gen.SyncResponseResultType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference implementation of @see LogCollector
 */
public class DefaultLogCollector implements LogCollector, LogProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultLogCollector.class);

    private LogUploadConfiguration    configuration;
    private LogStorage                storage;
    private LogStorageStatus          storageStatus;
    private LogUploadStrategy         uploadStrategy;
    private LogUploadFailoverStrategy failoverStrategy;
    private final LogTransport        transport;

    private final Map<Integer, Long> timeoutMap = new LinkedHashMap<>();

    private boolean isUploading = false;

    public DefaultLogCollector(LogTransport transport, KaaChannelManager manager) {
        configuration = new DefaultLogUploadConfiguration.Builder()
                                .setBatchVolume(BATCH_VOLUME)
                                .setVolumeThreshold(SINK_THRESHOLD)
                                .setMaximumAllowedVolume(MAX_STORAGE_SIZE)
                                .setLogUploadTimeout(UPLOAD_TIMEOUT)
                                .build();
        storage = new MemoryLogStorage(configuration.getBatchVolume());
        storageStatus = (LogStorageStatus)storage;
        uploadStrategy = new DefaultLogUploadStrategy();
        failoverStrategy = new DefaultLogUploadFailoverStrategy(manager);
        this.transport = transport;
    }

    @Override
    public synchronized void addLogRecord(SuperRecord record) throws IOException {
        storage.addLogRecord(new LogRecord(record));

        if (!isDeliveryTimeout()) {
            processUploadDecision(uploadStrategy.isUploadNeeded(
                                        configuration, storageStatus));
        }
    }

    @Override
    public void setUploadStrategy(LogUploadStrategy strategy) {
        if (strategy != null) {
            uploadStrategy = strategy;
            LOG.info("New log upload strategy was set");
        }
    }

    @Override
    public void setFailoverStrategy(LogUploadFailoverStrategy strategy) {
        if (strategy != null) {
            failoverStrategy = strategy;
            LOG.info("New failover strategy was set");
        }
    }

    @Override
    public void setStorage(LogStorage storage) {
        if (storage != null) {
            this.storage = storage;
            this.storageStatus = null;
            LOG.info("New log storage was set");
        }
    }

    @Override
    public void setStorageStatus(LogStorageStatus status) {
        if (status != null) {
            this.storageStatus = status;
            LOG.info("New log storage status was set");
        }
    }

    @Override
    public void setConfiguration(LogUploadConfiguration configuration) {
        if (configuration != null) {
            this.configuration = configuration;
            LOG.info("New log configuration was set");
            processUploadDecision(uploadStrategy.isUploadNeeded(configuration, storageStatus));
        }
    }

    @Override
    public void fillSyncRequest(LogSyncRequest request) {
        LogBlock group = null;
        synchronized (storage) {
            group = storage.getRecordBlock(configuration.getBatchVolume());
            isUploading = false;
        }

        if (group != null) {
            List<LogRecord> recordList = group.getRecords();

            if (!recordList.isEmpty()) {
                LOG.trace("Sending {} log records", recordList.size());

                List<LogEntry> logs = new LinkedList<>();
                for (LogRecord record : recordList) {
                    logs.add(new LogEntry(ByteBuffer.wrap(record.getData())));
                }

                request.setRequestId(group.getBlockId());
                request.setLogEntries(logs);

                timeoutMap.put(group.getBlockId(), System.currentTimeMillis() +
                                        configuration.getLogUploadTimeout() * 1000);
            }
        } else {
            LOG.warn("Log group is null: storage is empty or log group size is too small");
        }
    }

    @Override
    public synchronized void onLogResponse(LogSyncResponse logSyncResponse) throws IOException {
        if (logSyncResponse.getDeliveryStatuses() != null){
            for (LogDeliveryStatus response : logSyncResponse.getDeliveryStatuses()){
                if (response.getResult() == SyncResponseResultType.SUCCESS) {
                    storage.removeRecordBlock(response.getRequestId());
                } else {
                    storage.notifyUploadFailed(response.getRequestId());
                    failoverStrategy.onFailure(response.getErrorCode());
                }

                timeoutMap.remove(response.getRequestId());
            }

            processUploadDecision(uploadStrategy.isUploadNeeded(configuration, storageStatus));
        }
    }

    private void processUploadDecision(LogUploadStrategyDecision decision) {
        switch (decision) {
        case UPLOAD:
            if (failoverStrategy.isUploadApproved() && !isUploading) {
                isUploading = true;
                transport.sync();
            }
            break;
        case CLEANUP:
            storage.removeOldestRecord(configuration.getMaximumAllowedVolume());
            break;
        case NOOP:
        default:
            break;
        }
    }

    private boolean isDeliveryTimeout() {
        boolean isTimeout = false;
        long currentTime = System.currentTimeMillis();

        for (Map.Entry<Integer, Long> logRequest : timeoutMap.entrySet()) {
            if (currentTime >= logRequest.getValue()) {
                isTimeout = true;
                break;
            }
        }

        if (isTimeout) {
            LOG.info("Log delivery timeout detected");

            for (Map.Entry<Integer, Long> logRequest : timeoutMap.entrySet()) {
                storage.notifyUploadFailed(logRequest.getKey());
            }

            timeoutMap.clear();
            failoverStrategy.onTimeout();
        }

        return isTimeout;
    }
}