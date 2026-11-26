<script setup>
// 官网“常见问题 + 口腔小贴士”：问答与系统规则一致（预约申请规则见报告 6.11，计价见 11.4）
import { ref } from 'vue'
import * as Icons from '@element-plus/icons-vue'
import { SITE } from '../portalData.js'

const emit = defineEmits(['book'])

const FAQ = [
  {
    q: '提交预约申请后，多久会有人联系我？',
    a: `所选门店的前台会在营业时间（${SITE.hours}）内按提交先后回电，与您商定牙医和具体时间。确认后才算预约成功，结果会显示在“我的预约”里；联系不上时，前台会写明原因。`
  },
  {
    q: '一次可以提交几条申请？可以约多远的日期？',
    a: '同一账号最多同时有 3 条待确认的申请，期望日期可以选今天起 30 天内。申请只表达就诊意愿，不会占用牙医的时间，正式安排以前台确认为准。'
  },
  {
    q: '换一家门店看牙，需要重新建档、带病历吗？',
    a: '不需要。三家门店共用一份患者档案，牙医可以查阅您在任何门店已确认的就诊记录和过敏信息。首次到 ADC 就诊时，如有外院病历，请带来供牙医参考。'
  },
  {
    q: '费用怎么计算？预约时选的项目要付钱吗？',
    a: '预约时选的项目只是参考，不收费。费用按牙医实际记录的治疗项目和数量，乘以就诊当天的标准单价计算，每行小计四舍五入到分后相加。每次就诊开一张结算单，可以用上面的“费用估算”先算一算。'
  },
  {
    q: '想取消或改期怎么办？',
    a: '申请还在“待确认”时，可以在“我的预约”里自己撤回；前台已经确认的预约，请直接致电门店取消或改期。'
  },
  {
    q: '我的过敏史和病历谁能看到？',
    a: '诊疗资料只有工作人员按岗位权限查看，过敏信息由接诊牙医当面核实后记录。官网账号只用于预约，看不到诊疗记录和费用明细。'
  },
  {
    q: '注册时收不到短信验证码？',
    a: '本网站是《系统分析与设计》课程项目的演示网站，没有接入短信服务，注册和找回密码的验证码统一为 123456。请不要填写真实的个人信息。'
  }
]
const open = ref(0)
const toggle = (i) => (open.value = open.value === i ? -1 : i)

const TIPS = [
  { icon: 'Brush', title: '每天两次，每次两分钟', text: '刷毛与牙龈呈 45 度，小幅度水平颤动，内侧、外侧和咬合面都要刷到。' },
  { icon: 'Connection', title: '牙缝交给牙线', text: '牙刷很难清洁牙齿之间的缝隙，每天用一次牙线或牙缝刷，能减少邻面龋和牙龈出血。' },
  { icon: 'Calendar', title: '半年检查一次', text: '早期龋齿和牙周问题往往没有症状，定期检查和洁牙可以及早发现，治疗也更简单。' },
  { icon: 'IceDrink', title: '冷热敏感别拖着', text: '喝冷饮时牙齿酸痛，可能是牙本质敏感，也可能是龋齿，建议尽早请牙医检查原因。' }
]
const tip = ref(0)
</script>

<template>
  <section id="faq" class="section tinted">
    <div class="wrap">
      <div class="section-head reveal">
        <span class="kicker">常见问题</span>
        <h2>预约前，您可能想知道</h2>
        <p>以下答案与系统的实际规则一致，没找到想问的可以直接致电门店。</p>
      </div>
      <div class="faq-grid">
        <div class="faq reveal">
          <div v-for="(f, i) in FAQ" :key="f.q" class="qa" :class="{ open: open === i }">
            <button type="button" class="q" :aria-expanded="open === i" @click="toggle(i)">
              <span class="qn">Q{{ i + 1 }}</span>
              <span class="qt">{{ f.q }}</span>
              <el-icon class="chev"><Icons.ArrowDown /></el-icon>
            </button>
            <div class="a-wrap"><div class="a"><p>{{ f.a }}</p></div></div>
          </div>
        </div>

        <aside class="tips reveal">
          <div class="tips-head">
            <span class="tips-badge"><el-icon><Icons.Sunny /></el-icon>口腔小贴士</span>
            <span class="tips-page">{{ tip + 1 }} / {{ TIPS.length }}</span>
          </div>
          <Transition name="tip" mode="out-in">
            <div :key="tip" class="tip">
              <span class="tip-icon"><el-icon><component :is="Icons[TIPS[tip].icon]" /></el-icon></span>
              <h3>{{ TIPS[tip].title }}</h3>
              <p>{{ TIPS[tip].text }}</p>
            </div>
          </Transition>
          <div class="tips-nav">
            <button v-for="(t, i) in TIPS" :key="t.title" type="button" :class="{ on: i === tip }" :aria-label="t.title" @click="tip = i"></button>
            <span class="grow"></span>
            <button type="button" class="arrow" aria-label="上一条" @click="tip = (tip + TIPS.length - 1) % TIPS.length"><el-icon><Icons.ArrowLeft /></el-icon></button>
            <button type="button" class="arrow" aria-label="下一条" @click="tip = (tip + 1) % TIPS.length"><el-icon><Icons.ArrowRight /></el-icon></button>
          </div>
          <div class="tips-cta">
            <span>已经很久没检查了？</span>
            <a @click="emit('book')">预约一次口腔检查<el-icon><Icons.ArrowRight /></el-icon></a>
          </div>
        </aside>
      </div>
    </div>
  </section>
</template>

<style scoped>
.faq-grid { display: grid; grid-template-columns: 1.45fr 1fr; gap: 40px; align-items: start; }
.qa { background: #fff; border-radius: 16px; margin-bottom: 12px; box-shadow: 0 2px 8px rgba(16, 60, 56, 0.04); transition: box-shadow 0.3s; }
.qa.open { box-shadow: 0 16px 36px rgba(16, 60, 56, 0.1); }
.q {
  width: 100%; display: flex; align-items: center; gap: 14px; padding: 18px 22px;
  background: none; border: 0; cursor: pointer; text-align: left; font: inherit; color: var(--ink);
}
.qn { flex-shrink: 0; font-weight: 800; font-size: 14px; color: var(--p); background: var(--p-soft); border-radius: 8px; padding: 2px 8px; }
.qt { flex: 1; font-weight: 700; font-size: 16px; }
.chev { transition: transform 0.3s; color: var(--ink-3); }
.qa.open .chev { transform: rotate(180deg); color: var(--p); }
/* 用 grid 行高过渡做平滑展开 */
.a-wrap { display: grid; grid-template-rows: 0fr; transition: grid-template-rows 0.35s ease; }
.qa.open .a-wrap { grid-template-rows: 1fr; }
.a { overflow: hidden; }
.a p { margin: 0; padding: 0 22px 20px 64px; color: var(--ink-2); font-size: 15px; line-height: 1.85; }

.tips {
  position: sticky; top: 96px; border-radius: 24px; padding: 26px 28px 22px; color: #fff;
  background: radial-gradient(400px 220px at 100% 0%, rgba(255, 255, 255, 0.12), transparent 60%), linear-gradient(150deg, #13897f, #0a5c56);
  box-shadow: 0 30px 60px rgba(10, 92, 86, 0.25);
}
.tips-head { display: flex; justify-content: space-between; align-items: center; }
.tips-badge { display: inline-flex; align-items: center; gap: 6px; font-weight: 700; font-size: 14px; background: rgba(255, 255, 255, 0.16); padding: 5px 12px; border-radius: 999px; }
.tips-page { font-size: 13px; opacity: 0.75; font-variant-numeric: tabular-nums; }
.tip { min-height: 190px; padding: 22px 0 8px; }
.tip-icon { width: 54px; height: 54px; border-radius: 16px; display: grid; place-items: center; font-size: 26px; background: #fff; color: var(--p-deep); }
.tip h3 { font-size: 22px; margin: 16px 0 8px; }
.tip p { margin: 0; color: rgba(255, 255, 255, 0.85); line-height: 1.8; }
.tips-nav { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.tips-nav > button:not(.arrow) { width: 8px; height: 8px; border-radius: 4px; border: 0; padding: 0; cursor: pointer; background: rgba(255, 255, 255, 0.4); transition: width 0.3s, background 0.3s; }
.tips-nav > button.on { width: 24px; background: #fff; }
.grow { flex: 1; }
.arrow { width: 34px; height: 34px; border-radius: 50%; border: 1px solid rgba(255, 255, 255, 0.35); background: none; color: #fff; cursor: pointer; display: grid; place-items: center; }
.arrow:hover { background: rgba(255, 255, 255, 0.12); }
.tips-cta { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 20px; padding-top: 16px; border-top: 1px solid rgba(255, 255, 255, 0.2); font-size: 14px; }
.tips-cta a { display: inline-flex; align-items: center; gap: 4px; font-weight: 700; color: #fff; cursor: pointer; }
.tip-enter-active, .tip-leave-active { transition: all 0.25s ease; }
.tip-enter-from { opacity: 0; transform: translateX(14px); }
.tip-leave-to { opacity: 0; transform: translateX(-14px); }

@media (max-width: 900px) {
  .faq-grid { grid-template-columns: 1fr; }
  .tips { position: static; }
}
@media (max-width: 600px) {
  .q { padding: 16px; gap: 10px; }
  .qt { font-size: 15px; }
  .a p { padding: 0 16px 16px; }
  .tips { padding: 22px 20px 18px; }
  .tip { min-height: 170px; }
}
</style>
