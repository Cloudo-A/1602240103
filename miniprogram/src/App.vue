<script>
export default {}
</script>

<style>
/* 全局样式：颜色与官网一致（青瓷绿 #0E7C74） */
page {
  --p: #0e7c74;
  --p-deep: #0a5c56;
  --p-soft: #e7f2f1;
  --ink: #17302d;
  --ink-2: #4a5d5a;
  --ink-3: #7b8b88;
  --line: #e3ebe9;
  background: #f3f7f6;
  color: var(--ink);
  font-size: 28rpx;
  line-height: 1.6;
  font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.card {
  background: #fff;
  border-radius: 24rpx;
  margin: 0 24rpx 24rpx;
  padding: 28rpx;
  box-shadow: 0 4rpx 16rpx rgba(16, 60, 56, 0.04);
}
.sec-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 40rpx 32rpx 20rpx;
}
.sec-title .t { font-size: 34rpx; font-weight: 700; }
.sec-title .s { font-size: 24rpx; color: var(--ink-3); }
.btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 88rpx;
  border-radius: 44rpx;
  font-size: 30rpx;
  font-weight: 600;
  background: var(--p);
  color: #fff;
}
.btn.ghost { background: #fff; color: var(--p); border: 2rpx solid var(--p); }
.btn.disabled { opacity: 0.5; }
.muted { color: var(--ink-3); }
.tag {
  display: inline-block;
  font-size: 22rpx;
  padding: 2rpx 14rpx;
  border-radius: 999rpx;
  background: var(--p-soft);
  color: var(--p-deep);
  margin: 0 10rpx 8rpx 0;
}
</style>
