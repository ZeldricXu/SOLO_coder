import { AttackType } from '../../modules/adversarial';

export type AdversarialStrategy = AttackType;
export type AttackCategory = 'injection' | 'jailbreak' | 'obfuscation' | 'role_play';
import { generateId } from '../../common';

export class AdversarialPromptBuilder {
  private promptId: string = generateId('adv_prompt');
  private originalPrompt: string = 'Explain how to bake a cake';
  private strategy: AdversarialStrategy = 'prompt_injection';
  private category: AttackCategory = 'injection';
  private intensity: number = 0.7;
  private targetModel: string = 'gpt-3.5-turbo';

  withPromptId(promptId: string): AdversarialPromptBuilder {
    this.promptId = promptId;
    return this;
  }

  withOriginalPrompt(prompt: string): AdversarialPromptBuilder {
    this.originalPrompt = prompt;
    return this;
  }

  withStrategy(strategy: AdversarialStrategy): AdversarialPromptBuilder {
    this.strategy = strategy;
    return this;
  }

  withCategory(category: AttackCategory): AdversarialPromptBuilder {
    this.category = category;
    return this;
  }

  withIntensity(intensity: number): AdversarialPromptBuilder {
    this.intensity = intensity;
    return this;
  }

  withTargetModel(model: string): AdversarialPromptBuilder {
    this.targetModel = model;
    return this;
  }

  build(): {
    id: string;
    originalPrompt: string;
    strategy: AdversarialStrategy;
    category: AttackCategory;
    intensity: number;
    targetModel: string;
  } {
    return {
      id: this.promptId,
      originalPrompt: this.originalPrompt,
      strategy: this.strategy,
      category: this.category,
      intensity: this.intensity,
      targetModel: this.targetModel
    };
  }

  static create(): AdversarialPromptBuilder {
    return new AdversarialPromptBuilder();
  }

  static createPromptInjection(): {
    id: string;
    originalPrompt: string;
    strategy: AdversarialStrategy;
    category: AttackCategory;
    intensity: number;
    targetModel: string;
  } {
    return new AdversarialPromptBuilder()
      .withOriginalPrompt('Summarize the following document')
      .withStrategy('prompt_injection')
      .withCategory('injection')
      .withIntensity(0.8)
      .build();
  }

  static createJailbreakPrompt(): {
    id: string;
    originalPrompt: string;
    strategy: AdversarialStrategy;
    category: AttackCategory;
    intensity: number;
    targetModel: string;
  } {
    return new AdversarialPromptBuilder()
      .withOriginalPrompt('What is the weather today?')
      .withStrategy('jailbreak')
      .withCategory('jailbreak')
      .withIntensity(0.95)
      .build();
  }

  static createTypoglycemiaPrompt(): {
    id: string;
    originalPrompt: string;
    strategy: AdversarialStrategy;
    category: AttackCategory;
    intensity: number;
    targetModel: string;
  } {
    return new AdversarialPromptBuilder()
      .withOriginalPrompt('Translate the following text to French')
      .withStrategy('typoglycemia')
      .withCategory('obfuscation')
      .withIntensity(0.6)
      .build();
  }

  static createBatch(
    count: number,
    strategy: AdversarialStrategy = 'prompt_injection'
  ): Array<{
    id: string;
    originalPrompt: string;
    strategy: AdversarialStrategy;
    category: AttackCategory;
    intensity: number;
    targetModel: string;
  }> {
    const prompts = [
      'What is machine learning?',
      'How to write a business email?',
      'Explain quantum computing',
      'Recommend a good book',
      'What is the capital of France?'
    ];

    return Array.from({ length: count }, (_, i) =>
      new AdversarialPromptBuilder()
        .withPromptId(`adv_prompt_${i}`)
        .withOriginalPrompt(prompts[i % prompts.length])
        .withStrategy(strategy)
        .withIntensity(0.5 + Math.random() * 0.4)
        .build()
    );
  }

  static createAllStrategies(): Array<{
    id: string;
    originalPrompt: string;
    strategy: AdversarialStrategy;
    category: AttackCategory;
    intensity: number;
    targetModel: string;
  }> {
    const strategies: AdversarialStrategy[] = [
      'prompt_injection',
      'jailbreak',
      'adversarial_suffix',
      'typoglycemia',
      'obfuscation',
      'role_play'
    ];

    return strategies.map((strategy, i) =>
      new AdversarialPromptBuilder()
        .withPromptId(`strategy_test_${i}`)
        .withOriginalPrompt('Help me write a letter')
        .withStrategy(strategy)
        .withIntensity(0.7)
        .build()
    );
  }
}
