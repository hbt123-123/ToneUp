<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NEmpty, NInput, NModal, NTag } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { apiGetNote, apiPutNote } from '@/api/endpoints'
import { humanizeError } from '@/api/http'
import { appMessage } from '@/utils/feedback'
import { listNoteIndex, upsertNoteIndex, type NoteIndexEntry } from '@/utils/notesIndex'
import VirtualList from '@/components/common/VirtualList.vue'
import { formatRelative } from '@/utils/format'

/**
 * 个人笔记（FR-NOTE-01~03）：
 * - 列表：题干预览 + 笔记摘要 + 更新时间；客户端关键词过滤；虚拟滚动；
 * - 编辑：PUT 覆盖式更新，保存前后一致性校验；
 * - 跳转原题进入工作台定位。
 */
const router = useRouter()
const auth = useAuthStore()

const entries = ref<NoteIndexEntry[]>([])
const keyword = ref('')

onMounted(() => {
  entries.value = listNoteIndex(auth.userId)
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return entries.value
  return entries.value.filter(
    (e) => e.snippet.toLowerCase().includes(kw) || String(e.questionId).includes(kw),
  )
})

/* ---------- 编辑弹窗 ---------- */

const editing = ref<NoteIndexEntry | null>(null)
const editLoading = ref(false)
const editSaving = ref(false)
const editText = ref('')
const savedSnapshot = ref('')

/** 竞态保护：连续点击两条笔记时仅采纳最后一次请求结果 */
let editSeq = 0

async function openEditor(entry: NoteIndexEntry): Promise<void> {
  const mySeq = ++editSeq
  editing.value = entry
  editText.value = ''
  savedSnapshot.value = ''
  editLoading.value = true
  try {
    const note = await apiGetNote(entry.questionId, entry.bankId)
    if (mySeq !== editSeq) return
    editText.value = note?.note_text ?? ''
    savedSnapshot.value = editText.value
  } catch (err) {
    if (mySeq !== editSeq) return
    appMessage.error(humanizeError(err))
    editing.value = null
  } finally {
    if (mySeq === editSeq) editLoading.value = false
  }
}

const dirty = computed(() => editText.value !== savedSnapshot.value)

async function saveEdit(): Promise<void> {
  if (!editing.value || !dirty.value || editSaving.value) return
  const content = editText.value
  editSaving.value = true
  try {
    await apiPutNote(editing.value.questionId, editing.value.bankId, content)
    savedSnapshot.value = content
    upsertNoteIndex(auth.userId, {
      bankId: editing.value.bankId,
      questionId: editing.value.questionId,
      noteText: content,
    })
    entries.value = listNoteIndex(auth.userId)
    appMessage.success('笔记已保存')
    editing.value = null
  } catch (err) {
    appMessage.error(humanizeError(err))
  } finally {
    editSaving.value = false
  }
}

function gotoQuestion(entry: NoteIndexEntry): void {
  localStorage.setItem('toneup:last-bank', entry.bankId)
  void router.push({
    name: 'practice',
    params: { bankId: entry.bankId },
    query: { qid: String(entry.questionId) },
  })
}
</script>

<template>
  <div class="content-inner notes-view">
    <div class="toolbar tu-card">
      <n-input v-model:value="keyword" placeholder="搜索笔记内容或题号" clearable size="small" class="kw" />
      <span class="count text-secondary">共 {{ entries.length }} 条笔记</span>
    </div>

    <div v-if="filtered.length > 0" class="list-wrap tu-card">
      <virtual-list :items="filtered" :item-size="88">
        <template #default="{ item }">
          <div class="note-item option-row" @click="openEditor(item)">
            <div class="ni-main">
              <p class="snippet">{{ item.snippet }}</p>
              <div class="meta text-secondary">
                <n-tag size="tiny" round>{{ item.bankId }}</n-tag>
                <span>题目 #{{ item.questionId }}</span>
                <span>{{ formatRelative(new Date(item.updatedAt).toISOString()) }}</span>
              </div>
            </div>
            <div class="ni-ops">
              <n-button size="small" tertiary @click.stop="gotoQuestion(item)">跳转原题</n-button>
            </div>
          </div>
        </template>
      </virtual-list>
    </div>

    <n-empty v-else description="还没有笔记。刷题时在解析页随手记录吧。" class="empty-pad">
      <template #extra>
        <n-button size="small" @click="$router.push('/catalog')">去刷题</n-button>
      </template>
    </n-empty>

    <!-- 编辑与保存（FR-NOTE-02） -->
    <n-modal
      :show="editing !== null"
      preset="card"
      title="编辑笔记"
      style="max-width: 640px"
      @update:show="(v: boolean) => { if (!v) editing = null }"
    >
      <n-input
        v-model:value="editText"
        type="textarea"
        :rows="8"
        :loading="editLoading"
        placeholder="笔记正文（覆盖式保存）"
      />
      <template #footer>
        <div class="modal-foot">
          <span class="text-secondary">{{ dirty ? '有未保存修改' : '已与服务器一致' }}</span>
          <n-button type="primary" :loading="editSaving" :disabled="!dirty" @click="saveEdit">
            保存（覆盖更新）
          </n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.notes-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
}

.kw {
  width: 280px;
}

.count {
  font-size: 13px;
  margin-left: auto;
}

.list-wrap {
  padding: 6px;
  height: calc(100vh - 240px);
  min-height: 300px;
  overflow-y: auto;
}

.note-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 12px;
  border-bottom: 1px solid var(--tu-border);
  border-radius: var(--tu-radius-control);
}

.snippet {
  margin: 0 0 5px;
  font-size: 15px;
  color: var(--tu-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}

.ni-ops {
  flex: none;
}

.modal-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.empty-pad {
  padding: 80px 0;
}
</style>
