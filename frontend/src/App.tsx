import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ask, fetchMeta, RequestError } from './api'
import type { Cell, ChatResponse, ChatStatus, Meta, ToolInfo } from './api'

const MAX_LENGTH = 500
const EXAMPLES = ['SKU-007의 현재 재고는?', 'SKU-012의 재고보유일수는?', '재고 어때?', 'SKU-007 재고를 0으로 바꿔줘']

const STATUS_TEXT: Record<Exclude<ChatStatus, 'OK'>, string> = {
  SQL_REJECTED: '안전 검사에서 거부된 SQL입니다.',
  SQL_EXECUTION_ERROR: '조회를 실행하지 못했습니다.',
  TOOL_REJECTED: '조회 도구에 넘길 값이 올바르지 않습니다.',
  FORMAT_ERROR: '모델의 답변 형식이 올바르지 않습니다.',
  LLM_UNAVAILABLE: '모델 서버에 연결하지 못했습니다.',
}

interface Exchange {
  id: number
  question: string
  response?: ChatResponse
  error?: string
}

function formatCell(value: Cell): string {
  if (value === null) {
    return '-'
  }
  // 연도나 번호 컬럼이 2,026처럼 보이지 않게 자릿수 구분 기호는 넣지 않는다.
  return typeof value === 'number'
    ? value.toLocaleString('ko-KR', { maximumFractionDigits: 4, useGrouping: false })
    : String(value)
}

function ResultTable({ response }: { response: ChatResponse }) {
  const columns = response.columns ?? []
  const rows = response.rows ?? []
  if (rows.length === 0) {
    return <p className="muted">조건에 맞는 데이터가 없습니다.</p>
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map((column, index) => (
              <th key={index}>{column}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((cell, cellIndex) => (
                <td key={cellIndex} className={typeof cell === 'number' ? 'num' : undefined}>
                  {formatCell(cell)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ToolCall({ tool }: { tool: ToolInfo }) {
  const args = Object.entries(tool.args ?? {})
    .map(([key, value]) => `${key}=${value}`)
    .join(', ')
  return (
    <pre>
      {tool.name ?? '(이름 없음)'}({args})
    </pre>
  )
}

/** 문장은 숫자의 출처가 확인된 것만 온다. 확인은 출처만 보고 뜻은 보지 않으므로 "검증된 답"이라고 부르지 않는다. */
function AnswerSentence({ response }: { response: ChatResponse }) {
  if (response.answerStatus === 'VERIFIED' && response.answer) {
    return (
      <div className="sentence">
        <p>{response.answer}</p>
        <p className="muted small">문장의 숫자가 조회 결과나 질문에 있는 값인지만 확인했습니다. 내용이 맞는지는 표로 확인해 주세요.</p>
      </div>
    )
  }
  if (response.answerStatus === 'REJECTED') {
    return <p className="muted small">답변 문장에 조회 결과에서 확인되지 않는 숫자가 있어 표만 보여 드립니다.</p>
  }
  return null
}

function Answer({ response }: { response: ChatResponse }) {
  const meta = `${(response.elapsedMs / 1000).toFixed(1)}초 · 모델 호출 ${response.attempts}회 · ${response.promptVersion}`

  if (response.status !== 'OK') {
    return (
      <div className="answer">
        <span className="badge badge-error">{response.status}</span>
        <p className="error-text">{STATUS_TEXT[response.status]}</p>
        {response.message && <p className="muted">{response.message}</p>}
        {response.sql && <pre>{response.sql}</pre>}
        {response.tool && <ToolCall tool={response.tool} />}
        <p className="meta">{meta}</p>
      </div>
    )
  }
  if (response.action === 'sql' || response.action === 'tool') {
    return (
      <div className="answer">
        <span className="badge">조회 결과 {response.rowCount ?? 0}행</span>
        <AnswerSentence response={response} />
        {response.truncated && <p className="muted">결과가 많아 앞부분만 표시했습니다.</p>}
        <ResultTable response={response} />
        {response.action === 'tool' && response.tool ? (
          <details>
            <summary>근거: 조회 도구 (기준일 {response.asOfDate})</summary>
            <ToolCall tool={response.tool} />
          </details>
        ) : (
          <details>
            <summary>근거 SQL (기준일 {response.asOfDate})</summary>
            <pre>{response.sql}</pre>
          </details>
        )}
        <p className="meta">{meta}</p>
      </div>
    )
  }
  return (
    <div className="answer">
      <span className={response.action === 'refuse' ? 'badge badge-refuse' : 'badge badge-clarify'}>
        {response.action === 'refuse' ? '답할 수 없는 요청' : '확인이 필요해요'}
      </span>
      <p>{response.message}</p>
      <p className="meta">{meta}</p>
    </div>
  )
}

export default function App() {
  const [question, setQuestion] = useState('')
  const [exchanges, setExchanges] = useState<Exchange[]>([])
  const [loading, setLoading] = useState(false)
  const [meta, setMeta] = useState<Meta | null>(null)
  const [metaError, setMetaError] = useState<string | null>(null)
  const nextId = useRef(1)

  useEffect(() => {
    fetchMeta()
      .then(setMeta)
      .catch(() => setMetaError('백엔드에 연결하지 못했습니다.'))
  }, [])

  const trimmed = question.trim()
  const canSubmit = !loading && trimmed.length > 0 && trimmed.length <= MAX_LENGTH

  async function submit(text: string) {
    const id = nextId.current++
    setExchanges((previous) => [{ id, question: text }, ...previous])
    setLoading(true)
    try {
      const response = await ask(text)
      setExchanges((previous) => previous.map((item) => (item.id === id ? { ...item, response } : item)))
    } catch (error) {
      const message = error instanceof RequestError ? error.message : '알 수 없는 오류가 발생했습니다.'
      setExchanges((previous) => previous.map((item) => (item.id === id ? { ...item, error: message } : item)))
    } finally {
      setLoading(false)
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (canSubmit) {
      setQuestion('')
      void submit(trimmed)
    }
  }

  return (
    <div className="page">
      <header>
        <h1>SCM 질의 챗봇</h1>
        <p className="muted">재고, 판매, 예측에 대해 한국어로 물어보세요. 조회만 할 수 있습니다.</p>
      </header>

      <form onSubmit={onSubmit}>
        <label htmlFor="question" className="sr-only">
          질문
        </label>
        <input
          id="question"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="예: 지난주 서울센터 판매량은?"
          maxLength={MAX_LENGTH}
          autoComplete="off"
          autoFocus
        />
        <button type="submit" disabled={!canSubmit}>
          {loading ? '답변 중' : '질문하기'}
        </button>
      </form>

      <div className="examples">
        {EXAMPLES.map((example) => (
          <button key={example} type="button" className="chip" disabled={loading} onClick={() => void submit(example)}>
            {example}
          </button>
        ))}
      </div>

      <main aria-live="polite">
        {exchanges.map((exchange) => (
          <section key={exchange.id} className="card">
            <h2>{exchange.question}</h2>
            {exchange.response && <Answer response={exchange.response} />}
            {exchange.error && <p className="error-text">{exchange.error}</p>}
            {!exchange.response && !exchange.error && <p className="muted">답변을 만들고 있습니다.</p>}
          </section>
        ))}
      </main>

      <footer>
        {meta ? (
          <span>
            기준일 {meta.asOfDate} · 모델 {meta.model} · 프롬프트 {meta.promptVersion} · {meta.llmProvider}
          </span>
        ) : (
          <span>{metaError ?? '설정 정보를 불러오는 중입니다.'}</span>
        )}
      </footer>
    </div>
  )
}
