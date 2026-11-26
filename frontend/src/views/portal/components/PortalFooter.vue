<script setup>
// 官网页脚
import BrandMark from '@/components/BrandMark.vue'
import { SITE } from '../portalData.js'

defineProps({ clinics: { type: Array, default: () => [] } })
</script>

<template>
  <footer class="footer">
    <div class="wrap footer-grid">
      <div>
        <div class="logo">
          <BrandMark :size="34" inverse />
          <span class="logo-text"><b>{{ SITE.name }}</b><small>ANDREW'S DENTAL CHAIN</small></span>
        </div>
        <p class="foot-text">一份病历，连锁通用。让每一次就诊，都有据可查。</p>
      </div>
      <div>
        <h4>门店</h4>
        <p v-for="c in clinics" :key="c.id">{{ c.name }} · {{ c.postalAddress }}</p>
      </div>
      <div>
        <h4>联系</h4>
        <p>预约热线 {{ SITE.hotline }}</p>
        <p>{{ SITE.hours }}</p>
        <router-link to="/login" class="foot-staff">员工登录系统 →</router-link>
      </div>
    </div>
    <div class="wrap copyright">© 2026 {{ SITE.fullName }} · 系统分析与设计课程项目演示网站 · 部分图片来自 <a href="https://unsplash.com" target="_blank" rel="noopener">Unsplash</a></div>
  </footer>
</template>

<style scoped>
.footer {
  background: #0b2926;
  color: rgba(255, 255, 255, 0.72);
  padding: 64px 0 0;
  font-size: 14px;
}
.footer-grid {
  display: grid;
  grid-template-columns: 1.2fr 1.4fr 1fr;
  gap: 48px;
}
.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #fff;
}
.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.logo-text b {
  font-size: 19px;
  letter-spacing: 1px;
}
.logo-text small {
  font-size: 10px;
  letter-spacing: 2px;
  color: rgba(255, 255, 255, 0.6);
}
h4 {
  color: #fff;
  margin: 4px 0 14px;
  font-size: 16px;
}
p {
  margin: 0 0 8px;
}
.foot-text {
  margin-top: 16px;
  max-width: 280px;
}
.foot-staff {
  color: #9fe0d8;
  text-decoration: none;
  display: inline-block;
  margin-top: 8px;
}
.copyright {
  margin-top: 48px;
  padding-top: 20px;
  padding-bottom: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.45);
}
.copyright a { color: inherit; text-decoration: underline; }
@media (max-width: 900px) {
  .footer-grid {
    grid-template-columns: 1fr;
    gap: 28px;
  }
}
</style>
