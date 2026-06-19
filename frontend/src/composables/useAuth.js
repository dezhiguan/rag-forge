import { reactive, computed, readonly } from 'vue'

const state = reactive({
  accessToken: null,
  user: null,
  resetTicket: null,
})

export function useAuth() {
  return {
    state: readonly(state),
    isAuthenticated: computed(() => !!state.accessToken),
    setSession(token, user) {
      state.accessToken = token
      state.user = user
    },
    clearSession() {
      state.accessToken = null
      state.user = null
    },
    setResetTicket(ticket) {
      state.resetTicket = ticket
    },
    clearResetTicket() {
      state.resetTicket = null
    },
  }
}
