import { expect, test } from '@playwright/test'

async function mockJudgeDashboardApis(page: import('@playwright/test').Page) {
  const context = page.context()

  await context.route('**/api/v1/metrics/dashboard', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          kbCount: 0,
          documentCount: 0,
          chunkCount: 0,
          todayApiCalls: 0,
          avgLatencyMs: 0,
          hitRate: 0,
          recentActivities: [],
        },
      }),
    })
  })

  await context.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (!path.startsWith('/api/v1/evaluation/quality/')) {
      return route.continue()
    }
    let data
    if (path.endsWith('/overview')) {
      data = {
        kpis: {
          overallScore: 0.82,
          overallChange: 0.01,
          faithfulness: 0.78,
          faithfulnessChange: -0.03,
          contextPrecision: 0.91,
          contextPrecisionChange: 0,
          answerRelevance: 0.85,
          answerRelevanceChange: 0.01,
        },
        trend: [
          { date: '2026-06-20', overall: 0.8, faithfulness: 0.75, contextPrecision: 0.9, answerRelevance: 0.84, sampleCount: 10 },
          { date: '2026-06-21', overall: 0.82, faithfulness: 0.78, contextPrecision: 0.91, answerRelevance: 0.85, sampleCount: 11 },
        ],
        samples: { sampleCount: 21 },
        anomaly: { severity: 'NORMAL', reason: '' },
      }
    } else if (path.endsWith('/by-kb')) {
      data = [
        { kbId: 101, kbName: 'KB-001', overallScore: 0.62, trend: -0.04, sampleCount: 8 },
        { kbId: 102, kbName: 'KB-002', overallScore: 0.84, trend: 0.01, sampleCount: 13 },
      ]
    } else if (path.endsWith('/worst-cases')) {
      data = [
        {
          judgeResultId: 1001,
          answerLogId: 2001,
          query: '低分示例 Query 1',
          overallScore: 0.24,
          createdAt: '2026-06-20T12:00:00',
          topIssue: '缺失上下文依据',
        },
      ]
    } else if (path.endsWith('/cost')) {
      data = {
        totalCny: 12.34,
        dailyAverageCny: 1.8,
        monthlyProjectedCny: 54.0,
        totalCalls: 18,
        failedCalls: 1,
        costBySource: {
          PRODUCTION: 8,
          GOLDEN_SET: 3,
          MANUAL: 1.34,
        },
      }
    } else if (path.endsWith('/case/1001')) {
      data = {
        judgeResultId: 1001,
        query: '低分示例 Query 1',
        answer: '这是一条测试答案[1]，包含引文。',
        chunks: [
          { chunkId: 11, score: 0.2, relevant: false, content: '不相关内容片段' },
          { chunkId: 12, score: 0.6, relevant: true, content: '相关参考片段' },
        ],
        scores: {
          faithfulness: 0.3,
          contextPrecision: 0.2,
          answerRelevance: 0.4,
          overallScore: 0.24,
        },
        judgeReasoning: '命中片段较少，存在较多未支持陈述。',
        improvements: ['补充更多可验证 chunk', '降低回答长度'],
        bottleneck: 'RETRIEVAL',
      }
    }
    if (data === undefined) {
      return route.continue()
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data }),
    })
  })
}

async function loginForNavigation(page: import('@playwright/test').Page) {
  await mockJudgeDashboardApis(page)

  await page.context().route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          access_token: 'admin-token',
          user: {
            displayName: 'admin@ragforge.cn',
            ragRole: 'ADMIN',
            scopes: ['rag:dashboard:read', 'rag:eval:write', 'rag:eval:manage'],
          },
        },
      }),
    })
  })

  await page.context().route('**/api/auth/refresh', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          accessToken: 'admin-refresh-token',
          user: {
            displayName: 'admin@ragforge.cn',
            ragRole: 'ADMIN',
            scopes: ['rag:dashboard:read', 'rag:eval:write', 'rag:eval:manage'],
          },
        },
      }),
    })
  })

  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.getByLabel('账号 / 手机号 / 邮箱').fill('admin@ragforge.cn')
  await page.getByLabel('密码').fill('Admin123!')
  await page.getByRole('button', { name: '登 录' }).click()
  await expect(page).toHaveURL(/\/$/)
}

test.describe('RAGForge quality dashboard (J6)', () => {
  test('login and open quality dashboard, then show KPI trend arrows', async ({ page }) => {
    await loginForNavigation(page)
    await page.goto('/evaluation/quality', { waitUntil: 'domcontentloaded' })

    await expect(page.getByText('质量看板')).toBeVisible()
    await expect(page.getByText('综合质量')).toBeVisible()
    const kpiCards = page.locator('.kpi-card')
    await expect(kpiCards).toHaveCount(4)
    for (const title of ['综合质量', '答案忠实度', '上下文精度', '答案相关性']) {
      await expect(kpiCards.filter({ hasText: title }).locator('.kpi-trend')).toContainText(/[↑↓→]/)
    }
  })
})
