package com.datamasker.shamir;

import com.datamasker.domain.shamir.algorithm.ShamirSecretSharing;
import com.datamasker.domain.shamir.model.KeyShard;
import com.datamasker.domain.shamir.model.SecretRecoveryResult;
import com.datamasker.testdata.ShamirTestDataMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Shamir密钥分片 - 数据一致性保障测试")
class ShamirSecretSharingConsistencyTest {

    private ShamirSecretSharing shamirSecretSharing;
    private BigInteger prime;

    @BeforeEach
    void setUp() {
        shamirSecretSharing = new ShamirSecretSharing();
        prime = shamirSecretSharing.generatePrime(256);
    }

    @Nested
    @DisplayName("分片-恢复一致性测试")
    class SplitReconstructConsistency {

        @Test
        @DisplayName("完整分片集合恢复后与原始密钥完全一致")
        void shouldReconstructOriginalSecretWithAllShares() {
            BigInteger originalSecret = new BigInteger(ShamirTestDataMother.DEFAULT_SECRET.getBytes());
            int threshold = ShamirTestDataMother.DEFAULT_THRESHOLD;
            int totalShares = ShamirTestDataMother.DEFAULT_TOTAL_SHARES;

            List<KeyShard> shards = shamirSecretSharing.split(originalSecret, threshold, totalShares, prime);
            SecretRecoveryResult result = shamirSecretSharing.reconstruct(shards, prime);

            assertThat(result.getRecoveredSecret()).isEqualTo(originalSecret);
            assertThat(result.getParticipantCount()).isEqualTo(totalShares);
        }

        @ParameterizedTest(name = "使用{0}个分片恢复（阈值={1}）")
        @CsvSource({"3,3", "4,3", "5,3", "4,4", "5,4"})
        @DisplayName("任意阈值数量的分片组合都能正确恢复密钥")
        void shouldReconstructWithAnyThresholdShares(int sharesToUse, int threshold) {
            BigInteger originalSecret = new BigInteger("test-secret-abc123".getBytes());
            int totalShares = 5;

            List<KeyShard> allShards = shamirSecretSharing.split(originalSecret, threshold, totalShares, prime);

            List<List<KeyShard>> combinations = generateCombinations(allShards, sharesToUse);

            for (List<KeyShard> combination : combinations) {
                SecretRecoveryResult result = shamirSecretSharing.reconstruct(combination, prime);
                assertThat(result.getRecoveredSecret())
                        .as("组合 %s 应当恢复原始密钥",
                                combination.stream().map(KeyShard::getShardIndex).collect(Collectors.toList()))
                        .isEqualTo(originalSecret);
            }
        }

        @Test
        @DisplayName("分片顺序不影响恢复结果")
        void shouldReconstructRegardlessOfShareOrder() {
            BigInteger originalSecret = new BigInteger("order-independent-secret".getBytes());
            int threshold = 3;
            int totalShares = 5;

            List<KeyShard> shards = shamirSecretSharing.split(originalSecret, threshold, totalShares, prime);

            List<KeyShard> shuffledShards = new ArrayList<>(shards);
            Collections.shuffle(shuffledShards);

            SecretRecoveryResult originalResult = shamirSecretSharing.reconstruct(shards, prime);
            SecretRecoveryResult shuffledResult = shamirSecretSharing.reconstruct(shuffledShards, prime);

            assertThat(shuffledResult.getRecoveredSecret()).isEqualTo(originalResult.getRecoveredSecret());
            assertThat(shuffledResult.getRecoveredSecret()).isEqualTo(originalSecret);
        }
    }

    @Nested
    @DisplayName("阈值边界一致性测试")
    class ThresholdBoundaryConsistency {

        @Test
        @DisplayName("低于阈值的分片无法恢复正确密钥")
        void shouldFailToReconstructWithInsufficientShares() {
            BigInteger originalSecret = new BigInteger("highly-confidential-secret".getBytes());
            int threshold = 3;
            int totalShares = 5;

            List<KeyShard> allShards = shamirSecretSharing.split(originalSecret, threshold, totalShares, prime);
            List<KeyShard> insufficientShards = allShards.subList(0, threshold - 1);

            SecretRecoveryResult result = shamirSecretSharing.reconstruct(insufficientShards, prime);

            assertThat(result.getRecoveredSecret()).isNotEqualTo(originalSecret);
        }

        @ParameterizedTest(name = "阈值={0},总分片={1}")
        @CsvSource({"2,3", "3,5", "5,10", "10,20"})
        @DisplayName("不同阈值配置下的恢复一致性")
        void shouldMaintainConsistencyAcrossThresholdConfigs(int threshold, int totalShares) {
            BigInteger originalSecret = new BigInteger(("threshold-test-secret-" + threshold + "-" + totalShares).getBytes());

            List<KeyShard> shards = shamirSecretSharing.split(originalSecret, threshold, totalShares, prime);
            List<KeyShard> thresholdShares = shards.subList(0, threshold);

            SecretRecoveryResult result = shamirSecretSharing.reconstruct(thresholdShares, prime);

            assertThat(result.getRecoveredSecret()).isEqualTo(originalSecret);
            assertThat(result.getParticipantCount()).isEqualTo(threshold);
        }
    }

    @Nested
    @DisplayName("数据完整性保障测试")
    class DataIntegrityGuarantee {

        @Test
        @DisplayName("分片篡改后恢复结果不一致")
        void shouldDetectTamperedShare() {
            BigInteger originalSecret = new BigInteger("integrity-check-secret".getBytes());
            int threshold = 3;
            int totalShares = 5;

            List<KeyShard> shards = shamirSecretSharing.split(originalSecret, threshold, totalShares, prime);
            KeyShard tamperedShard = shards.get(0);
            tamperedShard.setShardData(tamperedShard.getShardData().add(BigInteger.ONE));

            SecretRecoveryResult result = shamirSecretSharing.reconstruct(shards, prime);

            assertThat(result.getRecoveredSecret()).isNotEqualTo(originalSecret);
        }

        @Test
        @DisplayName("空输入处理一致性")
        void shouldHandleEmptyInputConsistently() {
            BigInteger zeroSecret = BigInteger.ZERO;
            int threshold = 2;
            int totalShares = 3;

            List<KeyShard> shards = shamirSecretSharing.split(zeroSecret, threshold, totalShares, prime);

            for (int i = threshold; i <= totalShares; i++) {
                List<KeyShard> testShards = shards.subList(0, i);
                SecretRecoveryResult result = shamirSecretSharing.reconstruct(testShards, prime);
                assertThat(result.getRecoveredSecret())
                        .as("使用 %d 个分片恢复零值", i)
                        .isEqualByComparingTo(BigInteger.ZERO);
            }
        }

        @Test
        @DisplayName("大数值密钥恢复一致性")
        void shouldHandleLargeSecretConsistently() {
            BigInteger largeSecret = prime.subtract(BigInteger.ONE);
            int threshold = 3;
            int totalShares = 5;

            List<KeyShard> shards = shamirSecretSharing.split(largeSecret, threshold, totalShares, prime);
            SecretRecoveryResult result = shamirSecretSharing.reconstruct(shards.subList(0, threshold), prime);

            assertThat(result.getRecoveredSecret()).isEqualByComparingTo(largeSecret);
        }
    }

    @Nested
    @DisplayName("幂等性测试")
    class IdempotencyTest {

        @Test
        @DisplayName("多次恢复结果一致性")
        void shouldReturnSameResultOnMultipleReconstructions() {
            BigInteger originalSecret = new BigInteger("idempotency-test".getBytes());
            int threshold = 3;
            int totalShares = 5;

            List<KeyShard> shards = shamirSecretSharing.split(originalSecret, threshold, totalShares, prime);
            List<KeyShard> testShards = shards.subList(0, threshold);

            SecretRecoveryResult firstResult = shamirSecretSharing.reconstruct(testShards, prime);
            SecretRecoveryResult secondResult = shamirSecretSharing.reconstruct(testShards, prime);
            SecretRecoveryResult thirdResult = shamirSecretSharing.reconstruct(testShards, prime);

            assertThat(firstResult.getRecoveredSecret())
                    .isEqualTo(secondResult.getRecoveredSecret())
                    .isEqualTo(thirdResult.getRecoveredSecret())
                    .isEqualTo(originalSecret);
        }

        @Test
        @DisplayName("相同输入产生相同分片集合结构")
        void shouldGenerateConsistentShareStructure() {
            BigInteger secret = new BigInteger("structural-consistency".getBytes());
            int threshold = 3;
            int totalShares = 5;

            List<KeyShard> shards1 = shamirSecretSharing.split(secret, threshold, totalShares, prime);
            List<KeyShard> shards2 = shamirSecretSharing.split(secret, threshold, totalShares, prime);

            assertThat(shards1).hasSize(totalShares);
            assertThat(shards2).hasSize(totalShares);

            SecretRecoveryResult result1 = shamirSecretSharing.reconstruct(shards1.subList(0, threshold), prime);
            SecretRecoveryResult result2 = shamirSecretSharing.reconstruct(shards2.subList(0, threshold), prime);

            assertThat(result1.getRecoveredSecret()).isEqualTo(secret);
            assertThat(result2.getRecoveredSecret()).isEqualTo(secret);
        }
    }

    private <T> List<List<T>> generateCombinations(List<T> list, int k) {
        List<List<T>> result = new ArrayList<>();
        generateCombinationsHelper(list, k, 0, new ArrayList<>(), result);
        return result;
    }

    private <T> void generateCombinationsHelper(List<T> list, int k, int start,
                                                List<T> current, List<List<T>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < list.size(); i++) {
            current.add(list.get(i));
            generateCombinationsHelper(list, k, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }
}
