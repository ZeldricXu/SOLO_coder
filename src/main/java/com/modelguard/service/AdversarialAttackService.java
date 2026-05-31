package com.modelguard.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.dto.AdversarialAttackLaunchDTO;
import com.modelguard.dto.AdversarialPromptGenerateDTO;
import com.modelguard.dto.SecurityAssessmentCreateDTO;
import com.modelguard.entity.AdversarialAttack;
import com.modelguard.entity.AdversarialPrompt;
import com.modelguard.entity.SecurityAssessment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface AdversarialAttackService {

    Mono<AdversarialPrompt> generateAdversarialPrompt(AdversarialPromptGenerateDTO dto);

    Mono<List<AdversarialPrompt>> generateMultiplePrompts(AdversarialPromptGenerateDTO dto);

    Mono<AdversarialPrompt> getAdversarialPrompt(String promptId);

    Mono<Page<AdversarialPrompt>> listAdversarialPrompts(int page, int size, String targetModel,
                                                          String attackType, String attackSuccess,
                                                          String severity, String category);

    Mono<Void> deleteAdversarialPrompt(String promptId);

    Mono<AdversarialAttack> launchAttack(AdversarialAttackLaunchDTO dto);

    Mono<AdversarialAttack> getAttack(String attackId);

    Mono<Page<AdversarialAttack>> listAttacks(int page, int size, String targetModel, String status);

    Mono<AdversarialPrompt> evaluatePrompt(String promptId, Map<String, Object> modelResponse);

    String generatePromptInjection(String originalPrompt, Map<String, Object> params);

    String generateJailbreakPrompt(String originalPrompt, Map<String, Object> params);

    String generateRoleplayAttack(String originalPrompt, Map<String, Object> params);

    String generateObfuscationAttack(String originalPrompt, Map<String, Object> params);

    String generateMultiTurnAttack(String originalPrompt, Map<String, Object> params);

    String generateDataLeakageAttack(String originalPrompt, Map<String, Object> params);

    String generateRefusalSuppression(String originalPrompt, Map<String, Object> params);

    String generateAdversarialSuffix(String originalPrompt, Map<String, Object> params);

    boolean detectAttackSuccess(String adversarialPrompt, Map<String, Object> modelResponse, String attackType);

    double calculateConfidenceScore(String adversarialPrompt, Map<String, Object> modelResponse, String attackType);

    String determineSeverity(String attackType, boolean success, double confidence);

    String determineCategory(String attackType);

    Mono<SecurityAssessment> createSecurityAssessment(SecurityAssessmentCreateDTO dto);

    Mono<SecurityAssessment> getSecurityAssessment(String assessmentId);

    Mono<Page<SecurityAssessment>> listSecurityAssessments(int page, int size, String modelId, String riskLevel, String status);

    Mono<Map<String, Object>> getModelSecuritySummary(String modelId, String version);

    Map<String, Object> extractSensitiveData(Map<String, Object> modelResponse);

    List<String> getAvailableAttackStrategies();

    Map<String, Object> getAttackStrategyDetails(String strategy);

    Flux<AdversarialPrompt> generateBatchPrompts(String attackStrategy, List<String> originalPrompts,
                                                  String targetModel, String targetVersion,
                                                  Map<String, Object> params, String generatedBy);
}
