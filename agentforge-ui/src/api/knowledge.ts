/** RAG 知识库 / 文档 / 检索（契约 2.4，权限 rag:read / rag:write） */
import { http } from '@/utils/request'
import type {
  DocumentQuery,
  DocumentUploadResult,
  KbDocument,
  KnowledgeBase,
  KnowledgeBaseRequest,
  KnowledgeSearchRequest,
  PageQuery,
  PageResult,
  SearchHit,
} from '@/types'

export const knowledgeApi = {
  listBases(params: PageQuery) {
    return http.get<PageResult<KnowledgeBase>>('/knowledge-bases', { params })
  },
  createBase(data: KnowledgeBaseRequest) {
    return http.post<KnowledgeBase>('/knowledge-bases', data)
  },
  updateBase(id: number | string, data: KnowledgeBaseRequest) {
    return http.put<KnowledgeBase>(`/knowledge-bases/${id}`, data)
  },
  /** 级联删除文档与向量 */
  removeBase(id: number | string) {
    return http.delete<void>(`/knowledge-bases/${id}`)
  },
  listDocuments(id: number | string, params: DocumentQuery, silent = false) {
    return http.get<PageResult<KbDocument>>(`/knowledge-bases/${id}/documents`, { params, silent })
  },
  /** 异步解析，multipart file */
  uploadDocument(id: number | string, file: File) {
    const form = new FormData()
    form.append('file', file)
    return http.post<DocumentUploadResult>(`/knowledge-bases/${id}/documents`, form)
  },
  /** 含处理进度/错误 */
  getDocument(documentId: number | string, silent = false) {
    return http.get<KbDocument>(`/documents/${documentId}`, { silent })
  },
  removeDocument(documentId: number | string) {
    return http.delete<void>(`/documents/${documentId}`)
  },
  reindex(documentId: number | string) {
    return http.post<DocumentUploadResult>(`/documents/${documentId}/reindex`)
  },
  search(id: number | string, data: KnowledgeSearchRequest) {
    return http.post<SearchHit[]>(`/knowledge-bases/${id}/search`, data)
  },
}

export default knowledgeApi
