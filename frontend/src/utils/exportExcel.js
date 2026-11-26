// 导出 Excel（.xlsx）：只在点“导出”时才加载 exceljs，不影响页面打开速度
// 数据都来自页面已经拿到的接口结果，导出不做业务计算
//
// exportExcel({
//   filename: '取消就诊统计_2026-07-01至2026-09-30',
//   sheets: [{
//     name: '取消统计',
//     title: '患者取消就诊统计',
//     notes: ['统计期间：2026-07-01 至 2026-09-30', '口径：……'],   // 标题下的说明行
//     columns: [{ header: '诊所', key: 'clinic', width: 16 }, { header: '取消率', key: 'rate', type: 'percent' }],
//     rows: [{ clinic: '天河诊所', rate: 0.1667 }],
//     footer: ['合计……']                                           // 表格下方的说明行
//   }]
// })
// 列类型 type：text（默认）/ int / money（两位小数）/ quantity（去掉多余的 0）/ percent（0.00%，null 显示“不适用”）
import { ElMessage } from 'element-plus'
import { toApiDateTime } from '@shared/format.js'

const NUM_FMT = { int: '0', money: '#,##0.00', percent: '0.00%', quantity: '0.######' }
const HEADER_FILL = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFE7F2F1' } }
const BORDER = { style: 'thin', color: { argb: 'FFD5E2E0' } }

export async function exportExcel({ filename, sheets }) {
  let ExcelJS
  try {
    ExcelJS = (await import('exceljs')).default
  } catch {
    ElMessage.error('导出组件加载失败，请检查网络后重试')
    return false
  }
  const wb = new ExcelJS.Workbook()
  wb.creator = 'ADC 牙科连锁诊所运营管理系统'
  wb.created = new Date()

  for (const sheet of sheets) {
    const ws = wb.addWorksheet(sheet.name.slice(0, 31))
    const cols = sheet.columns
    ws.columns = cols.map((c) => ({ key: c.key, width: c.width || (c.type && c.type !== 'text' ? 12 : 16) }))

    let r = 1
    if (sheet.title) {
      ws.mergeCells(r, 1, r, cols.length)
      const cell = ws.getCell(r, 1)
      cell.value = sheet.title
      cell.font = { bold: true, size: 14 }
      ws.getRow(r).height = 22
      r += 1
    }
    for (const note of [...(sheet.notes || []), `导出时间：${toApiDateTime(new Date()).replace('T', ' ')}`]) {
      ws.mergeCells(r, 1, r, cols.length)
      const cell = ws.getCell(r, 1)
      cell.value = note
      cell.font = { size: 10, color: { argb: 'FF6B7B79' } }
      r += 1
    }
    r += 1 // 空一行

    const headerRow = ws.getRow(r)
    cols.forEach((c, i) => {
      const cell = headerRow.getCell(i + 1)
      cell.value = c.header
      cell.font = { bold: true }
      cell.fill = HEADER_FILL
      cell.border = { top: BORDER, bottom: BORDER, left: BORDER, right: BORDER }
      cell.alignment = { vertical: 'middle', horizontal: c.type && c.type !== 'text' ? 'right' : 'left' }
    })
    headerRow.height = 20
    const headerIndex = r
    r += 1

    for (const row of sheet.rows) {
      const excelRow = ws.getRow(r)
      cols.forEach((c, i) => {
        const cell = excelRow.getCell(i + 1)
        let v = typeof c.value === 'function' ? c.value(row) : row[c.key]
        if (c.type === 'percent' && (v === null || v === undefined)) v = '不适用'
        else if (c.type && c.type !== 'text' && v !== null && v !== undefined && v !== '') v = Number(v)
        cell.value = v === undefined ? null : v
        if (typeof v === 'number' && NUM_FMT[c.type]) cell.numFmt = NUM_FMT[c.type]
        if (c.type && c.type !== 'text') cell.alignment = { horizontal: 'right' }
        cell.border = { bottom: BORDER }
      })
      r += 1
    }

    if (sheet.footer?.length) {
      r += 1
      for (const line of sheet.footer) {
        ws.mergeCells(r, 1, r, cols.length)
        ws.getCell(r, 1).value = line
        ws.getCell(r, 1).font = { size: 10, color: { argb: 'FF6B7B79' } }
        r += 1
      }
    }

    ws.views = [{ state: 'frozen', ySplit: headerIndex }]
    if (sheet.rows.length) ws.autoFilter = { from: { row: headerIndex, column: 1 }, to: { row: headerIndex + sheet.rows.length, column: cols.length } }
  }

  const buffer = await wb.xlsx.writeBuffer()
  const blob = new Blob([buffer], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${filename}.xlsx`
  document.body.appendChild(a)
  a.click()
  setTimeout(() => {
    URL.revokeObjectURL(a.href)
    a.remove()
  }, 1000)
  ElMessage.success(`已导出“${filename}.xlsx”`)
  return true
}
