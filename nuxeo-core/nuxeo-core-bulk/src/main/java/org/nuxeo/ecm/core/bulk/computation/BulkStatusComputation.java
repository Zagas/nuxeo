/*
 * (C) Copyright 2018 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Funsho David
 */
package org.nuxeo.ecm.core.bulk.computation;

import static org.nuxeo.ecm.core.bulk.BulkComponent.BULK_KV_STORE_NAME;
import static org.nuxeo.ecm.core.bulk.BulkServiceImpl.STATUS;

import org.nuxeo.ecm.core.bulk.BulkCodecs;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.lib.stream.codec.Codec;
import org.nuxeo.lib.stream.computation.AbstractComputation;
import org.nuxeo.lib.stream.computation.ComputationContext;
import org.nuxeo.lib.stream.computation.Record;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;

/**
 * Saves the status into a key value store.
 * <p>
 * Inputs:
 * <ul>
 * <li>i1: Reads {@link BulkStatus} sharded by command id</li>
 * </ul>
 * Ouptuts: none
 * </p>
 *
 * @since 10.2
 */
public class BulkStatusComputation extends AbstractComputation {

    public BulkStatusComputation(String name) {
        super(name, 1, 0);
    }

    @Override
    public void processRecord(ComputationContext context, String inputStreamName, Record record) {
        KeyValueStore kvStore = Framework.getService(KeyValueService.class).getKeyValueStore(BULK_KV_STORE_NAME);
        Codec<BulkStatus> codec = BulkCodecs.getStatusCodec();
        BulkStatus recordStatus = codec.decode(record.getData());
        String key = recordStatus.getCommandId() + STATUS;
        BulkStatus status;
        if (recordStatus.isDelta()) {
            status = codec.decode(kvStore.get(key));
            status.merge(recordStatus);
        } else {
            status = recordStatus;
        }
        kvStore.put(key, codec.encode(status));
        context.askForCheckpoint();
    }
}