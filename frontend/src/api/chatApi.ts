import { apiRequest } from './client'
import type { ChatMessage, ChatPreviewResult, Conversation } from './types'

export const chatApi = {
  conversations: (connectionId: number) =>
    apiRequest<Conversation[]>(`/conversations?connectionId=${connectionId}`),
  messages: (conversationId: number) =>
    apiRequest<ChatMessage[]>(`/conversations/${conversationId}/messages`),
  preview: (payload: { connectionId: number; conversationId?: number; question: string }) =>
    apiRequest<ChatPreviewResult>('/chat/preview', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  removeConversation: (conversationId: number) =>
    apiRequest<void>(`/conversations/${conversationId}`, { method: 'DELETE' }),
}
