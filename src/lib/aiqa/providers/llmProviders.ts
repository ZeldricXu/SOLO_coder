import { LlmProvider, LlmCompletionRequest, LlmCompletionResponse, LlmProviderConfig } from './types';

export class OpenAIProvider implements LlmProvider {
  private apiKey: string;
  private model: string;
  private baseUrl: string;

  constructor(config: LlmProviderConfig) {
    this.apiKey = config.apiKey;
    this.model = config.model || 'gpt-3.5-turbo';
    this.baseUrl = config.baseUrl || 'https://api.openai.com/v1';
  }

  async complete(request: LlmCompletionRequest): Promise<LlmCompletionResponse> {
    const response = await fetch(`${this.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.apiKey}`,
      },
      body: JSON.stringify({
        model: this.model,
        messages: request.messages,
        temperature: request.temperature ?? 0.7,
        max_tokens: request.maxTokens ?? 1000,
      }),
    });

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(`OpenAI API error: ${response.status} ${errorData.error?.message || response.statusText}`);
    }

    const data = await response.json();

    return {
      content: data.choices?.[0]?.message?.content || '',
      usage: {
        promptTokens: data.usage?.prompt_tokens ?? 0,
        completionTokens: data.usage?.completion_tokens ?? 0,
        totalTokens: data.usage?.total_tokens ?? 0,
      },
    };
  }
}

export class AnthropicProvider implements LlmProvider {
  private apiKey: string;
  private model: string;
  private baseUrl: string;

  constructor(config: LlmProviderConfig) {
    this.apiKey = config.apiKey;
    this.model = config.model || 'claude-3-sonnet-20240229';
    this.baseUrl = config.baseUrl || 'https://api.anthropic.com/v1';
  }

  async complete(request: LlmCompletionRequest): Promise<LlmCompletionResponse> {
    const systemMessage = request.messages.find(m => m.role === 'system');
    const userMessages = request.messages.filter(m => m.role !== 'system');

    const response = await fetch(`${this.baseUrl}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': this.apiKey,
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model: this.model,
        ...(systemMessage ? { system: systemMessage.content } : {}),
        messages: userMessages.map(m => ({
          role: m.role === 'assistant' ? 'assistant' : 'user',
          content: m.content,
        })),
        temperature: request.temperature ?? 0.7,
        max_tokens: request.maxTokens ?? 1000,
      }),
    });

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(`Anthropic API error: ${response.status} ${errorData.error?.message || response.statusText}`);
    }

    const data = await response.json();

    return {
      content: data.content?.[0]?.text || '',
      usage: {
        promptTokens: data.usage?.input_tokens ?? 0,
        completionTokens: data.usage?.output_tokens ?? 0,
        totalTokens: (data.usage?.input_tokens ?? 0) + (data.usage?.output_tokens ?? 0),
      },
    };
  }
}

export function createLlmProvider(config: LlmProviderConfig): LlmProvider {
  switch (config.provider) {
    case 'openai':
      return new OpenAIProvider(config);
    case 'anthropic':
      return new AnthropicProvider(config);
    default:
      throw new Error(`Unsupported LLM provider: ${config.provider}`);
  }
}
