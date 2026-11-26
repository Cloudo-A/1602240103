<script setup>
// 官网“一份病历，连锁通用”：用一位示例患者跨门店就诊的四个步骤，演示系统的跨诊所协同（报告 1.3、4.2、UC05）
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as Icons from '@element-plus/icons-vue'

const STEPS = [
  { title: '越秀诊所首诊', icon: 'FirstAidKit', text: '陈女士第一次到越秀诊所。前台建立患者档案，周明远医生核实她对青霉素过敏，完成第一次根管治疗，前台当场开具结算单。' },
  { title: '换到天河诊所复诊', icon: 'Switch', text: '她搬家后想在离家近的天河诊所复诊。前台按电话找到同一份档案，不用重新建档，也不用她自带纸质病历。' },
  { title: '牙医查阅跨店病历', icon: 'Reading', text: '林嘉文医生打开病历，看到她在越秀的治疗记录，过敏提醒醒目标出，接着上次的进度继续治疗。' },
  { title: '复诊结算，总部汇总', icon: 'Tickets', text: '第二次治疗确认后开出新的结算单，单号可追溯。这次收入计入林嘉文医生的季度业绩，总部无需诊所人工报送。' }
]
const step = ref(0)
const playing = ref(true)
const root = ref()
let timer = 0
let visible = false
const DURATION = 5200

function schedule() {
  clearTimeout(timer)
  if (playing.value && visible) timer = setTimeout(() => { step.value = (step.value + 1) % STEPS.length; schedule() }, DURATION)
}
function pick(i) {
  step.value = i
  playing.value = false
  schedule()
}
function toggle() {
  playing.value = !playing.value
  schedule()
}
let io
onMounted(() => {
  io = new IntersectionObserver(([e]) => { visible = e.isIntersecting; schedule() }, { threshold: 0.35 })
  io.observe(root.value)
})
onBeforeUnmount(() => { clearTimeout(timer); io?.disconnect() })

const atTianhe = computed(() => step.value >= 1)
const COMPARE = [
  { old: '换门店要重新建档，过敏史靠患者口述', now: '全连锁一份档案，过敏信息由牙医核实后共享' },
  { old: '既往治疗记录留在原诊所的电脑里', now: '任何门店的牙医都能查阅已确认的就诊记录' },
  { old: '各店收据格式不一，季度业绩人工汇总', now: '统一结算单，收入自动归入接诊牙医' }
]
</script>

<template>
  <section id="journey" ref="root" class="journey">
    <div class="wrap">
      <div class="j-head reveal">
        <span class="kicker light">一份病历 · 连锁通用</span>
        <h2>换一家门店复诊，<br class="show-sm" />病历跟着您走</h2>
        <p>下面用一位示例患者的经历，演示系统怎样把三家门店连成一个整体。</p>
      </div>

      <div class="j-grid">
        <ol class="j-steps reveal">
          <li v-for="(s, i) in STEPS" :key="s.title" :class="{ on: i === step, done: i < step }" @click="pick(i)">
            <span class="j-dot"><el-icon><component :is="Icons[s.icon]" /></el-icon></span>
            <div class="j-txt">
              <b>{{ i + 1 }}. {{ s.title }}</b>
              <p>{{ s.text }}</p>
              <span v-if="i === step && playing" :key="'bar' + step" class="j-bar" :style="{ animationDuration: DURATION + 'ms' }"></span>
            </div>
          </li>
          <button type="button" class="j-play" @click="toggle">
            <el-icon><component :is="playing ? Icons.VideoPause : Icons.VideoPlay" /></el-icon>{{ playing ? '暂停自动播放' : '继续自动播放' }}
          </button>
        </ol>

        <div class="j-stage reveal">
          <!-- 两家门店与患者移动 -->
          <div class="route">
            <div class="store" :class="{ here: !atTianhe }"><el-icon><Icons.OfficeBuilding /></el-icon><span>越秀诊所</span></div>
            <div class="path"><span class="walker" :class="{ moved: atTianhe }"><el-icon><Icons.UserFilled /></el-icon></span></div>
            <div class="store" :class="{ here: atTianhe }"><el-icon><Icons.OfficeBuilding /></el-icon><span>天河诊所</span></div>
          </div>
          <Transition name="pop">
            <div v-if="step === 1" class="toast"><el-icon><Icons.Search /></el-icon>前台按电话 138****6021 找到已有档案，不重复建档</div>
          </Transition>

          <!-- 共享病历 -->
          <div class="record">
            <div class="rec-head">
              <span class="avatar">陈</span>
              <div><b>陈女士</b><small>患者档案 P000021 · 三家门店共用</small></div>
              <span class="cloud"><el-icon><Icons.Connection /></el-icon>已同步</span>
            </div>
            <div class="allergy" :class="{ flash: step === 2 }">
              <el-icon><Icons.WarningFilled /></el-icon>
              <span>过敏：青霉素</span>
              <small>周明远医生核实 · 11-03</small>
            </div>
            <div class="visits">
              <div class="visit" :class="{ reading: step === 2 }">
                <span class="v-dot"></span>
                <div>
                  <b>11-03 · 越秀诊所 · 周明远</b>
                  <p>根管治疗（第 1 次）· 局部麻醉 2 毫升</p>
                  <span class="inv">结算单 JS20261103-0002 · ¥670.00</span>
                </div>
                <Transition name="pop"><span v-if="step === 2" class="reader"><el-icon><Icons.View /></el-icon>林嘉文医生正在查阅</span></Transition>
              </div>
              <Transition name="grow">
                <div v-if="step === 3" class="visit new">
                  <span class="v-dot"></span>
                  <div>
                    <b>11-10 · 天河诊所 · 林嘉文</b>
                    <p>根管治疗（第 2 次）· 已避开青霉素类药物</p>
                    <span class="inv">结算单 JS20261110-0001 · ¥600.00</span>
                  </div>
                </div>
              </Transition>
            </div>
            <Transition name="pop">
              <div v-if="step === 3" class="hq"><el-icon><Icons.DataAnalysis /></el-icon>收入 ¥600.00 自动计入林嘉文医生第 4 季度业绩</div>
            </Transition>
          </div>
          <p class="j-note">示意动画，患者与数据均为示例</p>
        </div>
      </div>

      <div class="compare reveal">
        <div class="c-col old">
          <span class="c-tag">以前 · 各店独立</span>
          <p v-for="c in COMPARE" :key="c.old"><el-icon><Icons.CircleClose /></el-icon>{{ c.old }}</p>
        </div>
        <div class="c-col now">
          <span class="c-tag">现在 · ADC 一体化</span>
          <p v-for="c in COMPARE" :key="c.now"><el-icon><Icons.CircleCheckFilled /></el-icon>{{ c.now }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.journey {
  padding: 100px 0;
  color: #fff;
  background:
    radial-gradient(700px 380px at 90% 10%, rgba(255, 255, 255, 0.07), transparent 60%),
    linear-gradient(160deg, #0b5d57 0%, #0a4a46 55%, #08393a 100%);
}
.j-head { text-align: center; max-width: 720px; margin: 0 auto 56px; }
.kicker.light { color: #9fe0d6; }
.j-head h2 { font-size: 36px; line-height: 1.35; margin: 0 0 14px; font-weight: 800; }
.j-head p { color: rgba(255, 255, 255, 0.78); margin: 0; }
.show-sm { display: none; }

.j-grid { display: grid; grid-template-columns: 0.95fr 1.05fr; gap: 56px; align-items: center; }
.j-steps { list-style: none; margin: 0; padding: 0; }
.j-steps li {
  display: flex; gap: 16px; padding: 16px 18px; border-radius: 16px; cursor: pointer;
  transition: background 0.3s, opacity 0.3s; opacity: 0.6;
}
.j-steps li:hover { opacity: 0.9; }
.j-steps li.on { background: rgba(255, 255, 255, 0.1); opacity: 1; }
.j-steps li.done { opacity: 0.8; }
.j-dot {
  flex-shrink: 0; width: 42px; height: 42px; border-radius: 50%; display: grid; place-items: center;
  background: rgba(255, 255, 255, 0.12); font-size: 20px; transition: background 0.3s, color 0.3s;
}
.j-steps li.on .j-dot { background: #fff; color: var(--p-deep); }
.j-txt { position: relative; flex: 1; }
.j-txt b { font-size: 17px; }
.j-txt p { margin: 4px 0 0; font-size: 14.5px; color: rgba(255, 255, 255, 0.8); line-height: 1.7; }
.j-bar {
  position: absolute; left: 0; bottom: -10px; height: 2px; width: 100%; background: #9fe0d6;
  transform-origin: left; animation: bar linear forwards;
}
@keyframes bar { from { transform: scaleX(0); } to { transform: scaleX(1); } }
.j-play {
  margin: 14px 0 0 18px; display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
  background: none; border: 1px solid rgba(255, 255, 255, 0.3); color: #fff; border-radius: 999px; padding: 6px 14px; font-size: 13px;
}

/* 舞台 */
.j-stage { position: relative; }
.route { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.store {
  display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-radius: 14px;
  background: rgba(255, 255, 255, 0.08); border: 1px solid rgba(255, 255, 255, 0.15); font-weight: 600;
  transition: all 0.4s;
}
.store.here { background: #fff; color: var(--p-deep); border-color: #fff; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.25); }
.path { position: relative; flex: 1; height: 2px; background: repeating-linear-gradient(90deg, rgba(255, 255, 255, 0.45) 0 8px, transparent 8px 14px); }
.walker {
  position: absolute; top: 50%; left: 0; width: 34px; height: 34px; margin-top: -17px; border-radius: 50%;
  background: #f6c56b; color: #6b4a12; display: grid; place-items: center; font-size: 18px;
  box-shadow: 0 0 0 6px rgba(246, 197, 107, 0.25); transition: left 1.2s cubic-bezier(0.6, 0, 0.3, 1), transform 1.2s;
}
.walker.moved { left: calc(100% - 34px); }
.toast {
  position: absolute; z-index: 2; left: 50%; top: -50px; transform: translateX(-50%); white-space: nowrap;
  display: flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 999px;
  background: #f6c56b; color: #4b3510; font-size: 13.5px; font-weight: 600; box-shadow: 0 10px 24px rgba(0, 0, 0, 0.25);
}

.record { background: #fff; color: var(--ink); border-radius: 22px; padding: 22px 24px; box-shadow: 0 40px 80px rgba(0, 0, 0, 0.3); }
.rec-head { display: flex; align-items: center; gap: 12px; padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.avatar { width: 44px; height: 44px; border-radius: 50%; display: grid; place-items: center; background: var(--p-soft); color: var(--p-deep); font-weight: 800; font-size: 18px; }
.rec-head b { display: block; font-size: 17px; }
.rec-head small { color: var(--ink-3); font-size: 12.5px; }
.cloud { margin-left: auto; white-space: nowrap; display: inline-flex; align-items: center; gap: 4px; font-size: 12.5px; color: var(--p); background: var(--p-soft); padding: 4px 10px; border-radius: 999px; }
.allergy {
  display: flex; align-items: center; gap: 8px; margin: 14px 0; padding: 10px 14px; border-radius: 12px;
  background: #fdf0ee; color: #b5432f; font-weight: 700; transition: box-shadow 0.3s;
}
.allergy small { margin-left: auto; font-weight: 500; color: #c27a6c; font-size: 12px; }
.allergy.flash { animation: flash 1.1s ease-in-out 2; }
@keyframes flash { 50% { box-shadow: 0 0 0 6px rgba(213, 85, 62, 0.25); } }
.visits { position: relative; padding-left: 18px; }
.visits::before { content: ''; position: absolute; left: 5px; top: 8px; bottom: 8px; width: 2px; background: var(--line); }
.visit { position: relative; display: flex; gap: 10px; padding: 10px 12px 12px 6px; border-radius: 12px; transition: background 0.3s; }
.visit.reading { background: #eef7f5; }
.visit.new { background: #f3faf8; }
.v-dot { position: absolute; left: -18px; top: 16px; width: 12px; height: 12px; border-radius: 50%; background: var(--p); border: 2px solid #fff; box-shadow: 0 0 0 2px var(--p); }
.visit b { font-size: 14.5px; }
.visit p { margin: 2px 0 6px; color: var(--ink-2); font-size: 13.5px; }
.inv { display: inline-block; font-size: 12px; color: var(--p-deep); background: var(--p-soft); padding: 2px 8px; border-radius: 6px; font-variant-numeric: tabular-nums; }
.reader { position: absolute; right: 10px; top: 10px; display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: #fff; background: var(--p); padding: 3px 9px; border-radius: 999px; }
.hq { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding: 10px 14px; border-radius: 12px; background: #fff6e5; color: #8a5a10; font-size: 13.5px; font-weight: 600; }
.j-note { text-align: right; font-size: 12px; color: rgba(255, 255, 255, 0.55); margin: 12px 4px 0; }

.compare { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 64px; }
.c-col { border-radius: 18px; padding: 22px 26px; }
.c-col.old { background: rgba(0, 0, 0, 0.18); }
.c-col.now { background: rgba(255, 255, 255, 0.1); border: 1px solid rgba(159, 224, 214, 0.35); }
.c-tag { display: inline-block; font-size: 13px; letter-spacing: 2px; font-weight: 700; margin-bottom: 10px; }
.old .c-tag { color: rgba(255, 255, 255, 0.6); }
.now .c-tag { color: #9fe0d6; }
.c-col p { display: flex; gap: 8px; align-items: flex-start; margin: 8px 0; font-size: 15px; }
.c-col .el-icon { margin-top: 4px; flex-shrink: 0; }
.old p { color: rgba(255, 255, 255, 0.65); }
.old .el-icon { color: #f0a090; }
.now .el-icon { color: #9fe0d6; }

.pop-enter-active, .pop-leave-active { transition: all 0.35s ease; }
.pop-enter-from, .pop-leave-to { opacity: 0; transform: translateY(6px); }
.toast.pop-enter-from, .toast.pop-leave-to { transform: translate(-50%, 6px); }
.grow-enter-active { transition: all 0.5s ease; }
.grow-leave-active { transition: all 0.3s ease; }
.grow-enter-from, .grow-leave-to { opacity: 0; transform: translateY(-8px); }

@media (max-width: 900px) {
  .j-grid { grid-template-columns: 1fr; gap: 32px; }
  .compare { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .journey { padding: 64px 0; }
  .j-head h2 { font-size: 26px; }
  .show-sm { display: inline; }
  .j-steps li { padding: 12px; gap: 12px; }
  .j-steps li:not(.on) p { display: none; }
  .store { padding: 8px 10px; font-size: 13.5px; }
  .toast { position: static; transform: none; white-space: normal; margin: -6px 0 14px; justify-content: center; text-align: center; border-radius: 14px; }
  .toast.pop-enter-from, .toast.pop-leave-to { transform: translateY(6px); }
  .cloud { display: none; }
  .record { padding: 18px 16px; }
  .allergy small { display: none; }
  .reader { position: static; margin-top: 6px; }
  .visit { flex-direction: column; }
  .c-col { padding: 18px; }
}
</style>
