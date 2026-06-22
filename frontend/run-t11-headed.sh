#!/bin/bash
# T11 Answer-as-LLM 有头模式测试脚本

set -e

echo "🚀 T11 Answer-as-LLM Playwright 有头测试"
echo "=========================================="
echo ""

# 检查环境
echo "📋 环境检查..."

if ! command -v npx &> /dev/null; then
    echo "❌ 错误: npx 未找到，请确保 Node.js 已安装"
    exit 1
fi

echo "✅ Node.js 环境正常"
echo ""

# 显示测试列表
echo "📋 T11 测试列表:"
npx playwright test tests/e2e/v5-acceptance/t11 --list
echo ""

# 询问用户选择
echo "请选择运行模式:"
echo "1) 运行所有 T11 测试 (headed)"
echo "2) 仅运行核心安全用例 (headed)"
echo "3) 运行单个测试文件 (headed)"
echo "4) 调试模式运行 (headed + debug)"
echo "5) 退出"
echo ""
read -p "输入选项 [1-5]: " choice

case $choice in
    1)
        echo "🧪 运行所有 T11 测试 (有头模式)..."
        npx playwright test tests/e2e/v5-acceptance/t11 --headed --workers=1
        ;;
    2)
        echo "🔒 运行核心安全用例..."
        echo ""
        echo "测试 1/3: PII 泄露拦截"
        npx playwright test tests/e2e/v5-acceptance/t11/t11-acc-04-pii-leak-guardrail.spec.ts --headed --workers=1
        echo ""
        echo "测试 2/3: SSE Retrieval PII 脱敏"
        npx playwright test tests/e2e/v5-acceptance/t11/t11-acc-05-sse-retrieval-pii-masked.spec.ts --headed --workers=1
        echo ""
        echo "测试 3/3: 流式取消验证"
        npx playwright test tests/e2e/v5-acceptance/t11/t11-acc-09-streaming-cancel.spec.ts --headed --workers=1
        ;;
    3)
        echo ""
        echo "可用测试文件:"
        ls -1 tests/e2e/v5-acceptance/t11/t11-acc-*.spec.ts | xargs -n1 basename
        echo ""
        read -p "输入测试文件名 (如 t11-acc-01-streaming-token-by-token.spec.ts): " testfile
        npx playwright test "tests/e2e/v5-acceptance/t11/$testfile" --headed --workers=1
        ;;
    4)
        echo "🐛 调试模式运行..."
        read -p "输入测试文件名: " testfile
        npx playwright test "tests/e2e/v5-acceptance/t11/$testfile" --headed --workers=1 --debug
        ;;
    5)
        echo "👋 退出"
        exit 0
        ;;
    *)
        echo "❌ 无效选项"
        exit 1
        ;;
esac

echo ""
echo "✅ 测试完成!"
echo "📊 报告位置:"
echo "   - HTML 报告: playwright-report/"
echo "   - 测试结果: test-results/v5-acceptance/t11/"
echo "   - 性能报告: test-results/v5-acceptance/t11/t11-acc-10-perf-report.json"
