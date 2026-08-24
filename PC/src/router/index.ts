import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { saveRedirectPath } from '@/api/token'

/** 路由表（§2.3）：全部路由级代码分割 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresAuth: false, title: '登录' },
  },
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { requiresAuth: true, title: '首页' },
  },
  {
    path: '/catalog',
    name: 'catalog',
    component: () => import('@/views/CatalogView.vue'),
    meta: { requiresAuth: true, title: '题库' },
  },
  {
    path: '/practice/:bankId',
    name: 'practice',
    component: () => import('@/views/PracticeView.vue'),
    meta: { requiresAuth: true, title: '刷题工作台' },
  },
  {
    path: '/review/today',
    name: 'review-today',
    component: () => import('@/views/ReviewTodayView.vue'),
    meta: { requiresAuth: true, title: '今日复习' },
  },
  {
    path: '/wrong-book',
    name: 'wrong-book',
    component: () => import('@/views/WrongBookView.vue'),
    meta: { requiresAuth: true, title: '错题本' },
  },
  {
    path: '/stats',
    name: 'stats',
    component: () => import('@/views/StatsView.vue'),
    meta: { requiresAuth: true, title: '统计' },
  },
  {
    path: '/notes',
    name: 'notes',
    component: () => import('@/views/NotesView.vue'),
    meta: { requiresAuth: true, title: '个人笔记' },
  },
  {
    path: '/ai-feedback',
    name: 'ai-feedback',
    component: () => import('@/views/AiFeedbackView.vue'),
    meta: { requiresAuth: true, title: 'AI 纠错' },
  },
  {
    path: '/admin',
    name: 'admin',
    component: () => import('@/views/AdminView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '管理' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

let sessionRestorePromise: Promise<boolean> | null = null

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 会话恢复（FR-AUTH-03）：有本地令牌但用户信息缺失时调一次 /auth/me
  if (auth.token && !auth.user && !sessionRestorePromise) {
    sessionRestorePromise = auth.restoreSession().finally(() => {
      setTimeout(() => {
        sessionRestorePromise = null
      }, 0)
    })
    await sessionRestorePromise
  }

  if (to.meta.requiresAuth !== false && !auth.isLoggedIn) {
    saveRedirectPath(to.fullPath)
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }

  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: 'home' }
  }

  // admin 角色校验（FR-ADM-01），角色来自 /api/auth/me
  if (to.meta.requiresAdmin === true && !auth.isAdmin) {
    return { name: 'home', query: { denied: 'admin' } }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · ToneUp` : 'ToneUp · 一潼上岸'
})

export default router
