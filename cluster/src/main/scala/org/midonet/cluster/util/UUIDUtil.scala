/*
 * Copyright 2014 Midokura SARL
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
 */
package org.midonet.cluster.util

import java.lang.reflect.Type
import java.util.{UUID => JUUID}
import javax.annotation.Nonnull

import scala.util.Random

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons.{UUID => PUUID}

object UUIDUtil {

    /**
     * Convert a java.util.UUID to a Protocol Buffers message.
     */
    implicit def toProto(uuid: JUUID): PUUID = {
        if (uuid == null) null
        else PUUID.newBuilder
                  .setMsb(uuid.getMostSignificantBits)
                  .setLsb(uuid.getLeastSignificantBits)
                  .build()
    }

    def toProto(@Nonnull msb: Long, @Nonnull lsb: Long): PUUID = {
        PUUID.newBuilder().setMsb(msb).setLsb(lsb).build()
    }

    def toProto(uuidStr: String): PUUID = {
        if (uuidStr == null) null
        else toProto(JUUID.fromString(uuidStr))
    }

    implicit def fromProto(uuid: PUUID): JUUID = {
        new JUUID(uuid.getMsb, uuid.getLsb)
    }

    implicit def asRichJavaUuid(uuid: JUUID): RichJavaUuid =
        new RichJavaUuid(uuid)

    class RichJavaUuid private[UUIDUtil](val uuid: JUUID) extends AnyVal {
        def asProto: PUUID = toProto(uuid)
    }

    implicit def asRichProtoUuid(uuid: PUUID): RichProtoUuid =
        new RichProtoUuid(uuid)

    class RichProtoUuid private[UUIDUtil](val uuid: PUUID) extends AnyVal {
        def asJava: JUUID = fromProto(uuid)

        /**
         * Can be used to deterministically generate UUIDs derived from
         * another UUID. For example, see inChainId() and outChainId() in
         * the ChainManager trait, which each XOR a device ID with a different
         * statically-generated UUID in order to generate unique, predictable
         * UUIDs for the device's chains.
         */
        def xorWith(msb: Long, lsb: Long): PUUID = {
            // These bits are metadata and should not be flipped.
            val msbMask = msb & 0xffffffffffff0fffL
            val lsbMask = lsb & 0x3fffffffffffffffL
            toProto(uuid.getMsb ^ msbMask, uuid.getLsb ^ lsbMask)
        }
    }

    def toString(id: PUUID) = if (id == null) "null" else fromProto(id).toString

    def randomUuidProto: PUUID = JUUID.randomUUID()

    sealed class Converter extends ZoomConvert.Converter[JUUID, PUUID] {
        override def toProto(value: JUUID, clazz: Type): PUUID =
            UUIDUtil.toProto(value)

        override def fromProto(value: PUUID, clazz: Type): JUUID =
            UUIDUtil.fromProto(value)
    }
}
