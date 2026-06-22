import { expect } from '@playwright/test'
import type { ChunkRaw, ChunkVO } from './t10-common'

export const VL_VECTOR_DIM = 2560

export function expectVlVectorDim(raw: ChunkRaw[]) {
  expect(raw.length, 'document should have chunks').toBeGreaterThan(0)
  for (const chunk of raw) {
    expect(chunk.vlVectorDim, `chunk ${chunk.chunkId} vl_vector dim`).toBe(VL_VECTOR_DIM)
  }
}

export function modalityCounts(raw: ChunkRaw[]) {
  return raw.reduce(
    (acc, chunk) => {
      const key = (chunk.modality || 'TEXT').toUpperCase()
      acc[key] = (acc[key] || 0) + 1
      return acc
    },
    {} as Record<string, number>,
  )
}

export function ocrSimilarity(actual: string, expectedTokens: string[], threshold = 0.8) {
  const text = (actual || '').replace(/\s+/g, '')
  if (!text) return 0
  const hits = expectedTokens.filter((token) => text.includes(token.replace(/\s+/g, ''))).length
  return hits / expectedTokens.length
}

export function expectOcrContains(actual: string, expectedTokens: string[], threshold = 0.8) {
  const score = ocrSimilarity(actual, expectedTokens, threshold)
  expect(score, `OCR "${actual}" should match tokens ${expectedTokens.join(', ')}`).toBeGreaterThanOrEqual(threshold)
}

export function imageChunks(chunks: ChunkVO[]) {
  return chunks.filter((c) => (c.chunkModality || c.modality || '').toUpperCase() === 'IMAGE')
}

export function textChunks(chunks: ChunkVO[]) {
  return chunks.filter((c) => (c.chunkModality || c.modality || 'TEXT').toUpperCase() === 'TEXT')
}

export function parsePrometheusCounter(text: string, metricPrefix: string): number {
  let total = 0
  for (const line of text.split('\n')) {
    if (line.startsWith('#') || !line.trim()) continue
    if (!line.startsWith(metricPrefix)) continue
    const value = Number(line.trim().split(/\s+/).pop())
    if (Number.isFinite(value)) total += value
  }
  return total
}

export function parsePrometheusSampleCount(text: string, metricPrefix: string): number {
  let count = 0
  for (const line of text.split('\n')) {
    if (line.startsWith('#') || !line.trim()) continue
    if (line.startsWith(metricPrefix)) count += 1
  }
  return count
}
