<template>
  <div class="page help-center">
    <section class="panel help-hero">
      <div>
        <div class="help-eyebrow">COPPER ERP 使用手册</div>
        <h1>帮助中心</h1>
        <p>按模块查看操作步骤、业务口径和常见问题。手册会随功能逐步补充。</p>
      </div>
      <el-input
        v-model="keyword"
        class="help-search"
        clearable
        :prefix-icon="Search"
        placeholder="搜索当前手册"
        aria-label="搜索当前手册"
      />
    </section>

    <div class="help-layout">
      <aside class="panel help-sidebar" aria-label="手册目录">
        <div class="help-sidebar-title">手册目录</div>
        <button
          v-for="manual in filteredManuals"
          :key="manual.id"
          type="button"
          class="help-manual-item"
          :class="{ 'is-active': manual.id === activeManual.id }"
          @click="selectManual(manual.id)"
        >
          <span>{{ manual.module }}</span>
          <strong>{{ manual.title }}</strong>
          <small>{{ manual.summary }}</small>
        </button>
        <el-empty v-if="!filteredManuals.length" description="没有匹配的手册内容" :image-size="72" />
        <div class="help-coming-soon">
          采购、销售、库存、结算、发票和会计等模块手册将陆续加入。
        </div>
      </aside>

      <main class="panel help-document">
        <header class="help-document-header">
          <div>
            <el-tag effect="plain">{{ activeManual.module }}</el-tag>
            <span>更新日期：{{ activeManual.updatedAt }}</span>
          </div>
          <nav class="help-related-routes" aria-label="相关功能入口">
            <span>相关入口</span>
            <el-button
              v-for="item in activeManual.relatedRoutes"
              :key="item.path"
              text
              type="primary"
              @click="router.push(item.path)"
            >
              {{ item.label }}
            </el-button>
          </nav>
        </header>
        <article
          ref="articleRef"
          class="help-markdown"
          @click="handleArticleClick"
          v-html="renderedManual"
        />
        <el-empty v-if="keyword.trim() && !manualMatches" description="当前手册中没有找到相关内容" />
      </main>
    </div>

    <el-backtop target=".content" :right="28" :bottom="28" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { helpManuals } from '@/help/manuals'
import { renderMarkdown } from '@/help/markdown'

const router = useRouter()
const keyword = ref('')
const activeManualId = ref(helpManuals[0].id)
const articleRef = ref<HTMLElement>()
const activeManual = computed(() => helpManuals.find((manual) => manual.id === activeManualId.value) ?? helpManuals[0])
const normalizedKeyword = computed(() => keyword.value.trim().toLocaleLowerCase('zh-CN'))
const manualMatches = computed(() => !normalizedKeyword.value || activeManual.value.source.toLocaleLowerCase('zh-CN').includes(normalizedKeyword.value))
const filteredManuals = computed(() => {
  if (!normalizedKeyword.value) return helpManuals
  return helpManuals.filter((manual) =>
    [manual.module, manual.title, manual.summary, manual.source]
      .some((value) => value.toLocaleLowerCase('zh-CN').includes(normalizedKeyword.value)),
  )
})
const renderedManual = computed(() => manualMatches.value ? highlight(renderMarkdown(activeManual.value.source), normalizedKeyword.value) : '')

async function selectManual(id: string) {
  activeManualId.value = id
  await nextTick()
  articleRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function highlight(html: string, search: string) {
  if (!search) return html
  const escaped = search.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return html.replace(new RegExp(`(?![^<]*>)(${escaped})`, 'giu'), '<mark>$1</mark>')
}

function handleArticleClick(event: MouseEvent) {
  const link = (event.target as HTMLElement).closest('a')
  if (!link) return
  const href = link.getAttribute('href') ?? ''
  if (href.startsWith('/')) {
    event.preventDefault()
    router.push(href)
  } else if (href.startsWith('http')) {
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  }
}
</script>

<style scoped>
.help-center {
  display: grid;
  gap: 16px;
  max-width: 1500px;
  margin: 0 auto;
}

.help-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 24px 28px;
  color: #fff;
  background:
    radial-gradient(circle at 78% 20%, rgb(222 116 59 / 28%), transparent 30%),
    linear-gradient(135deg, #0a2c43 0%, #10415e 100%);
  border: 0;
}

.help-hero h1 {
  margin: 4px 0 6px;
  font-size: 28px;
}

.help-hero p {
  margin: 0;
  color: rgb(255 255 255 / 76%);
}

.help-eyebrow {
  color: #f0b18c;
  font-size: var(--app-extra-small-font-size);
  font-weight: 700;
  letter-spacing: 0.12em;
}

.help-search {
  width: min(380px, 38vw);
}

.help-search :deep(.el-input__wrapper) {
  min-height: 42px;
  background: rgb(255 255 255 / 96%);
  box-shadow: none;
}

.help-layout {
  display: grid;
  grid-template-columns: 290px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.help-sidebar {
  position: sticky;
  top: 0;
  display: grid;
  gap: 8px;
  padding: 16px;
}

.help-sidebar-title {
  margin: 2px 4px 8px;
  color: #243744;
  font-weight: 700;
}

.help-manual-item {
  display: grid;
  gap: 5px;
  width: 100%;
  padding: 14px;
  text-align: left;
  color: #354956;
  background: #f7f9fb;
  border: 1px solid #e3e9ee;
  border-radius: 10px;
  cursor: pointer;
}

.help-manual-item:hover,
.help-manual-item.is-active {
  background: #fff8f3;
  border-color: #d87945;
}

.help-manual-item span {
  color: #b85b2d;
  font-size: var(--app-extra-small-font-size);
}

.help-manual-item strong {
  color: #172934;
}

.help-manual-item small {
  color: #71818b;
  font-size: var(--app-extra-small-font-size);
  line-height: 1.55;
}

.help-coming-soon {
  margin-top: 8px;
  padding: 12px;
  color: #71818b;
  background: #f7f9fb;
  border-radius: 8px;
  font-size: var(--app-extra-small-font-size);
  line-height: 1.65;
}

.help-document {
  min-width: 0;
  padding: 24px clamp(22px, 4vw, 54px) 56px;
}

.help-document-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 18px;
  border-bottom: 1px solid #e3e9ee;
  color: #71818b;
  font-size: var(--app-extra-small-font-size);
}

.help-document-header > div,
.help-related-routes {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.help-related-routes .el-button {
  margin: 0;
}

.help-markdown {
  max-width: 940px;
  margin: 0 auto;
  color: #283b47;
  font-size: var(--app-font-size);
  line-height: 1.8;
}

.help-markdown :deep(h1) {
  margin: 34px 0 18px;
  color: #112d3e;
  font-size: 30px;
  line-height: 1.3;
}

.help-markdown :deep(h2) {
  margin: 38px 0 14px;
  padding-bottom: 8px;
  color: #15384d;
  border-bottom: 1px solid #e5eaee;
  font-size: 22px;
}

.help-markdown :deep(h3) {
  margin: 26px 0 10px;
  color: #20485e;
  font-size: 17px;
}

.help-markdown :deep(p) {
  margin: 10px 0;
}

.help-markdown :deep(ul),
.help-markdown :deep(ol) {
  margin: 10px 0;
  padding-left: 26px;
}

.help-markdown :deep(li) {
  margin: 6px 0;
}

.help-markdown :deep(blockquote) {
  margin: 18px 0;
  padding: 14px 18px;
  color: #536a78;
  background: #f3f7f9;
  border-left: 4px solid #d87945;
  border-radius: 0 8px 8px 0;
}

.help-markdown :deep(code) {
  padding: 2px 5px;
  color: #a34e28;
  background: #fff2ea;
  border-radius: 4px;
}

.help-markdown :deep(mark) {
  padding: 1px 2px;
  color: inherit;
  background: #ffe39d;
  border-radius: 2px;
}

.help-markdown :deep(a) {
  color: var(--el-color-primary);
}

.help-markdown :deep(.help-table-wrap) {
  overflow-x: auto;
}

.help-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
}

.help-markdown :deep(th),
.help-markdown :deep(td) {
  padding: 9px 12px;
  text-align: left;
  border: 1px solid #dfe6eb;
}

.help-markdown :deep(th) {
  background: #f4f7f9;
}

@media (max-width: 900px) {
  .help-hero {
    align-items: stretch;
    flex-direction: column;
  }

  .help-search {
    width: 100%;
  }

  .help-layout {
    grid-template-columns: 1fr;
  }

  .help-sidebar {
    position: static;
  }

  .help-document {
    padding: 20px 18px 44px;
  }
}
</style>
