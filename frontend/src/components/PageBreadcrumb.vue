<template>
  <nav class="page-breadcrumb" aria-label="breadcrumb">
    <template v-for="(item, index) in items" :key="index">
      <span v-if="index > 0" class="sep" aria-hidden="true">/</span>
      <router-link
        v-if="item.to && !item.current"
        :to="item.to"
        class="crumb link"
      >
        {{ item.label }}
      </router-link>
      <span v-else class="crumb" :class="{ current: item.current }">{{ item.label }}</span>
    </template>
  </nav>
</template>

<script setup>
defineProps({
  items: {
    type: Array,
    default: () => [],
  },
})
</script>

<style scoped>
.page-breadcrumb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-muted);
}

.sep {
  color: #cbd5e1;
  user-select: none;
}

.crumb {
  max-width: 100%;
  word-break: break-word;
}

.crumb.link {
  color: var(--blue);
  text-decoration: none;
  font-weight: 500;
}

.crumb.link:hover {
  text-decoration: underline;
}

.crumb.current {
  color: var(--slate);
  font-weight: 600;
}
</style>
