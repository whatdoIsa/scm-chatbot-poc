export type Action = 'sql' | 'clarify' | 'refuse'
export type ChatStatus = 'OK' | 'SQL_REJECTED' | 'SQL_EXECUTION_ERROR' | 'FORMAT_ERROR' | 'LLM_UNAVAILABLE'
export type Cell = string | number | boolean | null

export interface ChatResponse {
  action: Action | null
  status: ChatStatus
  message: string | null
  sql: string | null
  columns: string[] | null
  rows: Cell[][] | null
  rowCount: number | null
  truncated: boolean
  asOfDate: string
  promptVersion: string
  model: string
  attempts: number
  elapsedMs: number
  chatLogId: number
}

export interface Meta {
  asOfDate: string
  promptVersion: string
  model: string
  llmProvider: string
}

interface ErrorResponse {
  code: string
  message: string
}

/** 요청 자체가 처리되지 못한 경우(검증 실패, 서버 오류, 연결 실패). 챗봇 처리 결과의 실패는 ChatResponse.status로 온다. */
export class RequestError extends Error {}

function isChatResponse(body: unknown): body is ChatResponse {
  return typeof body === 'object' && body !== null && 'status' in body && 'chatLogId' in body
}

export async function ask(question: string): Promise<ChatResponse> {
  let response: Response
  try {
    response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question }),
    })
  } catch {
    throw new RequestError('서버에 연결하지 못했습니다. 백엔드가 실행 중인지 확인해 주세요.')
  }
  const body: unknown = await response.json().catch(() => null)
  // 422와 502도 ChatResponse 본문을 가진다.
  if (isChatResponse(body)) {
    return body
  }
  const error = body as ErrorResponse | null
  if (error?.code === 'INVALID_REQUEST') {
    throw new RequestError('질문은 1자 이상 500자 이하로 입력해 주세요.')
  }
  // 개발 프록시는 백엔드가 꺼져 있으면 본문이 JSON이 아닌 5xx를 돌려준다.
  if (error === null && response.status >= 500) {
    throw new RequestError('서버에 연결하지 못했습니다. 백엔드가 실행 중인지 확인해 주세요.')
  }
  throw new RequestError(error?.message ?? `요청을 처리하지 못했습니다. (HTTP ${response.status})`)
}

export async function fetchMeta(): Promise<Meta> {
  const response = await fetch('/api/meta')
  if (!response.ok) {
    throw new RequestError(`설정 정보를 불러오지 못했습니다. (HTTP ${response.status})`)
  }
  return (await response.json()) as Meta
}
