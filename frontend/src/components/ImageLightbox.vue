<template>
  <Teleport to="body">
    <div v-if="src" class="img-lightbox" @click="$emit('close')">
      <img :src="src" class="img-lightbox-img" alt="" @click.stop />
      <button class="img-lightbox-close" aria-label="关闭" @click.stop="$emit('close')">×</button>
    </div>
  </Teleport>
</template>

<script setup>
defineProps({ src: { type: String, default: '' } })
defineEmits(['close'])
</script>

<style scoped>
.img-lightbox {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: rgba(15, 23, 42, 0.78);
  display: grid;
  place-items: center;
  padding: 32px;
  cursor: zoom-out;
}
.img-lightbox-img {
  max-width: 92vw;
  max-height: 92vh;
  border-radius: 8px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.45);
  cursor: default;
  background: #fff;
}
.img-lightbox-close {
  position: fixed;
  top: 18px;
  right: 22px;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  font-size: 26px;
  line-height: 1;
  cursor: pointer;
}
.img-lightbox-close:hover {
  background: rgba(255, 255, 255, 0.28);
}
</style>
