<template>
  <div class="panel-header page-header">
    <div class="page-header-main">
      <el-button class="page-back-button" text :icon="ArrowLeft" :title="backLabel" :aria-label="backLabel" @click="goBack" />
      <div class="page-header-titles">
        <div class="panel-title page-header-title">{{ title }}</div>
        <nav v-if="navigationTrail.length > 1" class="page-header-trail" aria-label="页面返回路径">
          <template v-for="(item, index) in navigationTrail" :key="item.path">
            <button v-if="index < navigationTrail.length - 1" type="button" class="page-header-trail-link" @click="router.push(item.path)">{{ item.label }}</button>
            <span v-else>{{ item.label }}</span>
            <span v-if="index < navigationTrail.length - 1" class="page-header-trail-separator">/</span>
          </template>
        </nav>
      </div>
    </div>
    <div v-if="showOriginAction || $slots.default" class="page-header-actions">
      <el-button v-if="showOriginAction" text type="primary" @click="goOrigin">返回起点</el-button>
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const props = withDefaults(
  defineProps<{
    title: string
    backTo: string
    backLabel?: string
  }>(),
  { backLabel: '返回' },
)

const router = useRouter()
const route = useRoute()

type NavigationTrailItem = { path: string; label: string }

const navigationTrail = computed<NavigationTrailItem[]>(() => {
  const parents: NavigationTrailItem[] = []
  const seen = new Set<string>()
  let from = route.query.from

  for (let depth = 0; depth < 8 && typeof from === 'string' && from.startsWith('/') && !from.startsWith('//'); depth += 1) {
    if (seen.has(from)) break
    seen.add(from)
    const resolved = router.resolve(from)
    parents.unshift({ path: resolved.fullPath, label: routeLabel(resolved) })
    from = resolved.query.from
  }

  return [...parents, { path: route.fullPath, label: props.title }]
})

const showOriginAction = computed(() => navigationTrail.value.length > 2)

function goBack() {
  router.push(props.backTo)
}

function goOrigin() {
  const origin = navigationTrail.value[0]
  if (origin) router.push(origin.path)
}

function routeLabel(target: ReturnType<typeof router.resolve>) {
  const base = String(target.meta.title || '关联页面')
  const titleParam = typeof target.meta.titleParam === 'string' ? target.meta.titleParam : 'id'
  const id = target.params[titleParam]
  const text = Array.isArray(id) ? id[0] : id
  return text ? `${base} #${text}` : base
}
</script>
