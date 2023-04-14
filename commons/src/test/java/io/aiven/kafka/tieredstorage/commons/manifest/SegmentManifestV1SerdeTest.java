/*
 * Copyright 2023 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.tieredstorage.commons.manifest;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;

import io.aiven.kafka.tieredstorage.commons.RsaKeyAwareTest;
import io.aiven.kafka.tieredstorage.commons.manifest.index.FixedSizeChunkIndex;
import io.aiven.kafka.tieredstorage.commons.manifest.serde.SecretKeyDeserializer;
import io.aiven.kafka.tieredstorage.commons.manifest.serde.SecretKeySerializer;
import io.aiven.kafka.tieredstorage.commons.security.EncryptionKeyProvider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentManifestV1SerdeTest extends RsaKeyAwareTest {
    static final FixedSizeChunkIndex INDEX =
        new FixedSizeChunkIndex(100, 1000, 110, 110);
    static final SecretKey SECRET_KEY = new SecretKeySpec(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, "AES");
    static final byte[] AAD = {10, 11, 12, 13};

    static final String WITH_ENCRYPTION_WITHOUT_SECRET_KEY_JSON =
        "{\"version\":\"1\","
            + "\"chunkIndex\":{\"type\":\"fixed\",\"originalChunkSize\":100,"
            + "\"originalFileSize\":1000,\"transformedChunkSize\":110,\"finalTransformedChunkSize\":110},"
            + "\"compression\":false,\"encryption\":{\"aad\":\"CgsMDQ==\"}}";
    static final String WITHOUT_ENCRYPTION_JSON =
        "{\"version\":\"1\","
            + "\"chunkIndex\":{\"type\":\"fixed\",\"originalChunkSize\":100,"
            + "\"originalFileSize\":1000,\"transformedChunkSize\":110,\"finalTransformedChunkSize\":110},"
            + "\"compression\":false}";

    ObjectMapper mapper;
    EncryptionKeyProvider encryptionKeyProvider;

    @BeforeEach
    void init() throws IOException {
        mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());

        try (final InputStream publicKeyFis = Files.newInputStream(publicKeyPem);
             final InputStream privateKeyFis = Files.newInputStream(privateKeyPem)) {
            encryptionKeyProvider = EncryptionKeyProvider.of(publicKeyFis, privateKeyFis);
        }

        final SimpleModule simpleModule = new SimpleModule();
        simpleModule.addSerializer(SecretKey.class, new SecretKeySerializer(encryptionKeyProvider::encryptKey));
        simpleModule.addDeserializer(SecretKey.class, new SecretKeyDeserializer(
            b -> new SecretKeySpec(encryptionKeyProvider.decryptKey(b), "AES")));
        mapper.registerModule(simpleModule);
    }

    @Test
    void withEncryption() throws JsonProcessingException {
        final SegmentManifest manifest = new SegmentManifestV1(INDEX, false,
            new SegmentEncryptionMetadataV1(SECRET_KEY, AAD));

        final String jsonStr = mapper.writeValueAsString(manifest);

        // Check that the key is encrypted.
        final ObjectNode deserializedJson = (ObjectNode) mapper.readTree(jsonStr);
        final byte[] encryptedKey = Base64.getDecoder().decode(
            deserializedJson.get("encryption").get("secretKey").asText());
        assertThat(new SecretKeySpec(encryptionKeyProvider.decryptKey(encryptedKey), "AES"))
            .isEqualTo(SECRET_KEY);

        // Remove the secret key--i.e. the variable part--and compare the JSON representation.
        ((ObjectNode) deserializedJson.get("encryption")).remove("secretKey");
        assertThat(mapper.writeValueAsString(deserializedJson)).isEqualTo(WITH_ENCRYPTION_WITHOUT_SECRET_KEY_JSON);

        // Check deserialization.
        final SegmentManifest deserializedManifest = mapper.readValue(jsonStr, SegmentManifest.class);
        assertThat(deserializedManifest).isEqualTo(manifest);
    }

    @Test
    void withoutEncryption() throws JsonProcessingException {
        final SegmentManifest manifest = new SegmentManifestV1(INDEX, false, null);

        final String jsonStr = mapper.writeValueAsString(manifest);

        // Compare the JSON representation.
        assertThat(jsonStr).isEqualTo(WITHOUT_ENCRYPTION_JSON);

        // Check deserialization.
        final SegmentManifest deserializedManifest = mapper.readValue(jsonStr, SegmentManifest.class);
        assertThat(deserializedManifest).isEqualTo(manifest);
    }
}
