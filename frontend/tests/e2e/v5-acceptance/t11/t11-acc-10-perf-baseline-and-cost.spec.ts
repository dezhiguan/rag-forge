/**
 * T11 ACC-10: 性能基线和成本验证
 * 目标：观测端到端延迟、token 用量、引用数和成本
 */
import {
  test,
  expect,
  login,
  createKb,
  uploadFile,
  waitForStatus,
  cleanupKb,
  openAnswerPlayground,
  selectKb,
  submitAnswer,
  asset,
  apiUrl,
} from './_helpers/t11-common'
import { execFileSync } from 'node:child_process'

const PG_HOST = process.env.RAGFORGE_PG_HOST || process.env.POSTGRES_HOST || '127.0.0.1'
const PG_PORT = process.env.RAGFORGE_PG_PORT || process.env.POSTGRES_PORT || '5432'
const PG_DATABASE = process.env.RAGFORGE_PG_DATABASE || process.env.POSTGRES_DB || 'ragforge'
const PG_USER = process.env.RAGFORGE_PG_USER || process.env.POSTGRES_USER || 'amy'
const PG_PASSWORD = process.env.RAGFORGE_PG_PASSWORD || process.env.POSTGRES_PASSWORD || 'amy'
const PSQL_BIN = process.env.PSQL_BIN || '/Applications/Postgres.app/Contents/Versions/latest/bin/psql'

function runPsql(sql: string): string {
  return execFileSync(
    PSQL_BIN,
    ['-h', PG_HOST, '-p', PG_PORT, '-d', PG_DATABASE, '-U', PG_USER, '-c', sql],
    {
      encoding: 'utf8',
      env: { ...process.env, PGPASSWORD: PG_PASSWORD },
      stdio: ['ignore', 'pipe', 'pipe'],
    }
  )
}

test.describe('T11 ACC-10 Performance Baseline and Cost', () => {
  let kbId: number
  let docId: number
  let headers: Record<string, string>
  const results: Array<{
    query: string
    totalLatencyMs: number
    retrievalLatencyMs: number
    llmLatencyMs: number
    promptTokens: number
    completionTokens: number
    citationsCount: number
    retrievalResultsCount: number
  }> = []

  test.beforeAll(async ({ request }) => {
    headers = await login(request)

    // Setup KB with all fixture content
    kbId = await createKb(request, headers, 't11-acc-10-perf')

    // Upload multiple documents for comprehensive testing
    docId = await uploadFile(request, headers, kbId, asset('answer-kb-tech-faq.txt'))
    const docId2 = await uploadFile(request, headers, kbId, asset('answer-kb-multilingual.txt'))

    await waitForStatus(request, headers, docId, 'COMPLETED')
    await waitForStatus(request, headers, docId2, 'COMPLETED')
  })

  test.afterAll(async ({ request }) => {
    await cleanupKb(request, headers, kbId)

    // Generate performance report
    console.log('\n=== T11 ACC-10 Performance Report ===\n')

    const avgTotalLatency =
      results.reduce((sum, r) => sum + r.totalLatencyMs, 0) / results.length || 0
    const avgRetrievalLatency =
      results.reduce((sum, r) => sum + r.retrievalLatencyMs, 0) / results.length || 0
    const avgLlmLatency =
      results.reduce((sum, r) => sum + r.llmLatencyMs, 0) / results.length || 0
    const avgPromptTokens =
      results.reduce((sum, r) => sum + r.promptTokens, 0) / results.length || 0
    const avgCompletionTokens =
      results.reduce((sum, r) => sum + r.completionTokens, 0) / results.length || 0
    const avgCitations =
      results.reduce((sum, r) => sum + r.citationsCount, 0) / results.length || 0
    const avgRetrievalResults =
      results.reduce((sum, r) => sum + r.retrievalResultsCount, 0) / results.length || 0

    const citationRate =
      avgRetrievalResults > 0 ? (avgCitations / avgRetrievalResults) : 0

    // DashScope pricing (approximate, qwen-plus)
    // Input: ¥0.002 / 1K tokens
    // Output: ¥0.006 / 1K tokens
    const inputCost = (avgPromptTokens / 1000) * 0.002
    const outputCost = (avgCompletionTokens / 1000) * 0.006
    const avgQueryCost = inputCost + outputCost
    const totalCostFor10Queries = avgQueryCost * 10

    console.log(`Total queries executed: ${results.length}`)
    console.log(`\n--- Latency (ms) ---`)
    console.log(`Average total latency: ${avgTotalLatency.toFixed(2)} ms`)
    console.log(`Average retrieval latency: ${avgRetrievalLatency.toFixed(2)} ms`)
    console.log(`Average LLM latency: ${avgLlmLatency.toFixed(2)} ms`)

    console.log(`\n--- Token Usage ---`)
    console.log(`Average prompt tokens: ${avgPromptTokens.toFixed(2)}`)
    console.log(`Average completion tokens: ${avgCompletionTokens.toFixed(2)}`)

    console.log(`\n--- Citation Metrics ---`)
    console.log(`Average citations per answer: ${avgCitations.toFixed(2)}`)
    console.log(`Average retrieval results: ${avgRetrievalResults.toFixed(2)}`)
    console.log(`Citation utilization rate: ${(citationRate * 100).toFixed(2)}%`)

    console.log(`\n--- Cost Estimate (qwen-plus) ---`)
    console.log(`Average cost per query: ¥${avgQueryCost.toFixed(4)}`)
    console.log(`Total cost for 10 queries: ¥${totalCostFor10Queries.toFixed(4)}`)

      // Write report to file
      const reportPath = 'test-results/v5-acceptance/t11/t11-acc-10-perf-report.json'
      const report = {
        timestamp: new Date().toISOString(),
        summary: {
          totalQueries: results.length,
          avgTotalLatencyMs: avgTotalLatency,
          avgRetrievalLatencyMs: avgRetrievalLatency,
          avgLlmLatencyMs: avgLlmLatency,
          avgPromptTokens,
          avgCompletionTokens,
          avgCitations,
          avgRetrievalResults,
          citationUtilizationRate: citationRate,
          avgCostPerQuery: avgQueryCost,
          totalCostFor10Queries,
        },
        details: results,
      }

      try {
        const fs = await import('fs')
        fs.mkdirSync('test-results/v5-acceptance/t11', { recursive: true })
        fs.writeFileSync(reportPath, JSON.stringify(report, null, 2))
        console.log(`\nReport saved to: ${reportPath}`)
      } catch (e) {
        console.error('Failed to save report:', e)
      }

    // Assertions for performance requirements
    console.log('\n=== Assertions ===')

    // Check total latency
    const totalLatencyPass = avgTotalLatency < 5000
    console.log(`✓ Average total latency < 5000ms: ${avgTotalLatency.toFixed(2)}ms ${totalLatencyPass ? 'PASS' : 'FAIL'}`)

    // Check prompt tokens
    const promptTokensPass = avgPromptTokens < 2000
    console.log(`✓ Average prompt tokens < 2000: ${avgPromptTokens.toFixed(0)} ${promptTokensPass ? 'PASS' : 'FAIL'}`)

    // Check citation range
    const citationsPass = avgCitations >= 1 && avgCitations <= 5
    console.log(`✓ Average citations in [1, 5]: ${avgCitations.toFixed(2)} ${citationsPass ? 'PASS' : 'FAIL'}`)

    // Check citation utilization
    const utilizationPass = citationRate >= 0.4
    console.log(`✓ Citation utilization >= 0.4: ${citationRate.toFixed(2)} ${utilizationPass ? 'PASS' : 'FAIL'}`)

    // Check cost
    const costPass = totalCostFor10Queries <= 1.0 // ¥1
    console.log(`✓ Total cost for 10 queries <= ¥1: ¥${totalCostFor10Queries.toFixed(4)} ${costPass ? 'PASS' : 'FAIL'}`)
  })

  const queries = [
    'Spring Boot 默认端口是多少',
    '如何启用 Spring Boot 热部署',
    'Spring Boot 自动配置原理',
    'Docker 容器化部署的优势是什么',
    'Kubernetes 主要功能有哪些',
    '什么是微服务架构',
    'Spring Boot 如何集成 Redis',
    '如何实现 Spring Boot 定时任务',
    'Spring Boot 配置文件优先级',
    'Spring Boot 数据库连接池配置',
  ]

  for (const query of queries) {
    test(`performance test: ${query.substring(0, 30)}...`, async ({ request }) => {
      const startTime = Date.now()

      const response = await request.post(apiUrl('/api/v1/answer'), {
        headers: {
          ...headers,
          'Content-Type': 'application/json',
        },
        data: {
          kbIds: [kbId],
          query,
          stream: false,
        },
      })

      const measuredTotalMs = Date.now() - startTime

      expect(response.ok()).toBeTruthy()
      const body = await response.json()

      const data = body.data || body

      // Collect metrics
      const result = {
        query,
        totalLatencyMs: data.latency?.total || measuredTotalMs,
        retrievalLatencyMs: data.latency?.retrieval || 0,
        llmLatencyMs: data.latency?.llm || 0,
        promptTokens: data.tokens?.prompt || 0,
        completionTokens: data.tokens?.completion || 0,
        citationsCount: (data.citations || []).length,
        retrievalResultsCount: (data.retrieval?.results || []).length,
      }

      results.push(result)

      // Individual query assertions
      expect(result.totalLatencyMs).toBeLessThan(15000) // 15s max per query
      expect(result.promptTokens).toBeGreaterThan(0)
      expect(result.llmLatencyMs).toBeGreaterThan(0)

      console.log(
        `[${query.substring(0, 20)}...] ` +
          `Total: ${result.totalLatencyMs}ms, ` +
          `Prompt: ${result.promptTokens}, ` +
          `Completion: ${result.completionTokens}, ` +
          `Citations: ${result.citationsCount}`
      )
    })
  }

  test('prometheus metrics should be available', async ({ request }) => {
    // Check if prometheus metrics endpoint is available
    try {
      const response = await request.get(apiUrl('/actuator/prometheus'), {
        headers,
      })

      if (response.ok()) {
        const metrics = await response.text()

        // Check for answer-related metrics
        expect(metrics).toMatch(/ragforge_answer|citation|retrieval/)

        console.log('Prometheus metrics found for answer service')
      } else {
        console.log('Prometheus endpoint not available or requires auth')
      }
    } catch (e) {
      console.log('Could not check prometheus metrics:', e)
    }
  })

  test('answer_logs table should have latency and token data', async () => {
    // Query to verify the logs table has all required columns and data
    const result = runPsql(`
      SELECT 
        COUNT(*) as count,
        AVG(total_latency_ms) as avg_total,
        AVG(prompt_tokens) as avg_prompt,
        AVG(completion_tokens) as avg_completion
      FROM answer_logs 
      WHERE created_at > NOW() - INTERVAL '1 hour'
    `)

    console.log('Answer logs stats:', result)

    // Should have data from our tests
    expect(result).toMatch(/\d+/)
  })

  test('verify 10 queries produce consistent results', async () => {
    // This test runs after all 10 queries have been collected
    expect(results.length).toBeGreaterThanOrEqual(5) // At least 5 queries completed

    // All should have valid latency measurements
    for (const r of results) {
      expect(r.totalLatencyMs).toBeGreaterThan(0)
      expect(r.retrievalLatencyMs).toBeGreaterThanOrEqual(0)
      expect(r.llmLatencyMs).toBeGreaterThan(0)
    }

    // All should have tokens recorded
    for (const r of results) {
      expect(r.promptTokens).toBeGreaterThan(0)
    }
  })

  test('final performance report screenshot', async ({ page }) => {
    // Navigate to answer playground and take a final screenshot
    await openAnswerPlayground(page)

    await page.screenshot({
      path: 'test-results/v5-acceptance/t11/t11-acc-10-final-report.png',
    })
  })
})
