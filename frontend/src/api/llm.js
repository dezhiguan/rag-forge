import request from './request'

export function llmGenerate(messages, model = 'qwen-plus', temperature = 0.3) {
  return request({
    url: '/llm/generate',
    method: 'post',
    data: { messages, model, temperature },
  })
}
