const form = document.querySelector('#export-form');
const inputPicker = document.querySelector('#input-files');
const dropZone = document.querySelector('#drop-zone');
const fileSummary = document.querySelector('#file-summary');
const fileList = document.querySelector('#file-list');
const fileCount = document.querySelector('#file-count');
const fileSize = document.querySelector('#file-size');
const fileError = document.querySelector('#file-error');
const submitButton = document.querySelector('#submit-button');
const statusTitle = document.querySelector('#status-title');
const statusDetail = document.querySelector('#status-detail');
const progressWrap = document.querySelector('#progress-wrap');
const progressBar = document.querySelector('#progress-bar');
const progressText = document.querySelector('#progress-text');
const picsPicker = document.querySelector('#pics-files');
const picsSummary = document.querySelector('#pics-summary');
const maxUploadLabel = document.querySelector('#upload-limit');

let selectedFiles = [];
let maxUploadBytes = Infinity;

function fileKey(file) {
  return `${file.name}:${file.size}:${file.lastModified}`;
}

function addFiles(files) {
  const supported = /\.(csv|tsv|xlsx|xls)$/i;
  const existing = new Set(selectedFiles.map(fileKey));
  let rejected = 0;
  for (const file of files) {
    if (!supported.test(file.name)) {
      rejected += 1;
    } else if (!existing.has(fileKey(file))) {
      selectedFiles.push(file);
      existing.add(fileKey(file));
    }
  }
  fileError.textContent = rejected ? `已忽略 ${rejected} 个不支持的文件。` : '';
  renderFiles();
}

function renderFiles() {
  fileList.replaceChildren();
  const total = selectedFiles.reduce((sum, file) => sum + file.size, 0);
  fileSummary.hidden = selectedFiles.length === 0;
  fileCount.textContent = `${selectedFiles.length} 个文件`;
  fileSize.textContent = formatBytes(total);

  selectedFiles.forEach((file, index) => {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.className = 'file-name';
    name.textContent = file.name;
    name.title = `${file.name} · ${formatBytes(file.size)}`;

    const type = document.createElement('span');
    const details = isDetailsFile(file.name);
    type.className = `file-type${details ? ' details' : ''}`;
    type.textContent = details ? 'DETAILS' : 'FRAME LIST';

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'remove-file';
    remove.setAttribute('aria-label', `移除 ${file.name}`);
    remove.textContent = '×';
    remove.addEventListener('click', () => {
      selectedFiles.splice(index, 1);
      renderFiles();
    });

    li.append(name, type, remove);
    fileList.append(li);
  });
}

function isDetailsFile(name) {
  const base = name.replace(/\.[^.]+$/, '').toLowerCase();
  return base.includes('details') || base.replace(/[^a-z0-9]+/g, ' ').trim().includes('vs cpm');
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 1024) return `${bytes || 0} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes;
  let unit = -1;
  do {
    value /= 1024;
    unit += 1;
  } while (value >= 1024 && unit < units.length - 1);
  return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`;
}

inputPicker.addEventListener('change', () => {
  addFiles(inputPicker.files);
  inputPicker.value = '';
});

for (const eventName of ['dragenter', 'dragover']) {
  dropZone.addEventListener(eventName, event => {
    event.preventDefault();
    dropZone.classList.add('dragging');
  });
}
for (const eventName of ['dragleave', 'drop']) {
  dropZone.addEventListener(eventName, event => {
    event.preventDefault();
    dropZone.classList.remove('dragging');
  });
}
dropZone.addEventListener('drop', event => addFiles(event.dataTransfer.files));

document.querySelector('#convert-usd').addEventListener('change', event => {
  document.querySelector('#rates-field').hidden = !event.target.checked;
});

for (const radio of document.querySelectorAll('input[name="photographyMode"]')) {
  radio.addEventListener('change', () => {
    document.querySelector('#photography-custom-field').hidden = radio.value !== 'custom' || !radio.checked;
  });
}

picsPicker.addEventListener('change', () => {
  const files = [...picsPicker.files];
  const bytes = files.reduce((sum, file) => sum + file.size, 0);
  picsSummary.textContent = files.length ? `${files.length} 个文件 · ${formatBytes(bytes)}` : '未选择；这是可选项';
});

async function loadServerCapabilities() {
  try {
    const response = await fetch('/api/health', { cache: 'no-store' });
    if (!response.ok) throw new Error('health check failed');
    const health = await response.json();
    maxUploadBytes = health.maxUploadBytes;
    maxUploadLabel.textContent = `单次上限 ${formatBytes(maxUploadBytes)}`;
    const remote = document.querySelector('#fetch-remote');
    const row = document.querySelector('#remote-images-row');
    const help = document.querySelector('#remote-images-help');
    remote.disabled = !health.allowRemoteImages;
    row.classList.toggle('disabled', !health.allowRemoteImages);
    help.textContent = health.allowRemoteImages ? '仅对可信的 FRAMEIMAGEPATH 启用' : '此部署已关闭；可改用 PICS 文件夹';
  } catch {
    maxUploadLabel.textContent = '服务连接异常';
    statusTitle.textContent = '暂时无法连接导出服务';
    statusDetail.textContent = '请刷新页面或联系部署管理员。';
  }
}

form.addEventListener('submit', event => {
  event.preventDefault();
  fileError.textContent = '';
  if (!form.reportValidity()) return;
  if (selectedFiles.length === 0) {
    fileError.textContent = '请至少选择一个 frame list 文件。';
    dropZone.scrollIntoView({ behavior: 'smooth', block: 'center' });
    return;
  }
  if (selectedFiles.every(file => isDetailsFile(file.name))) {
    fileError.textContent = '不能只上传 frame-details；还需要至少一个 frame list。';
    return;
  }

  const pics = [...picsPicker.files];
  const estimatedBytes = [...selectedFiles, ...pics].reduce((sum, file) => sum + file.size, 0);
  if (estimatedBytes > maxUploadBytes) {
    fileError.textContent = `文件合计 ${formatBytes(estimatedBytes)}，超过 ${formatBytes(maxUploadBytes)} 的单次上限。`;
    return;
  }

  const body = new FormData(form);
  body.delete('inputFiles');
  body.delete('picsFiles');
  for (const file of selectedFiles) body.append('inputFiles', file, file.name);
  for (const file of pics) body.append('picsFiles', file, file.webkitRelativePath || file.name);
  body.set('convertBudgetToUsd', document.querySelector('#convert-usd').checked ? 'true' : 'false');
  body.set('fetchPicsFromLinks', document.querySelector('#fetch-remote').checked ? 'true' : 'false');

  setBusy(true);
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/export');
  xhr.responseType = 'blob';
  xhr.upload.addEventListener('progress', uploadEvent => {
    if (!uploadEvent.lengthComputable) return;
    const percentage = Math.min(100, Math.round(uploadEvent.loaded / uploadEvent.total * 100));
    progressBar.classList.remove('indeterminate');
    progressBar.style.width = `${percentage}%`;
    progressText.textContent = `正在上传 ${percentage}%`;
    if (percentage === 100) {
      statusTitle.textContent = '服务器正在生成工作簿';
      statusDetail.textContent = '大文件的合并、图片和 Excel 排版可能需要几分钟。';
      progressText.textContent = '正在处理';
      progressBar.style.width = '';
      progressBar.classList.add('indeterminate');
    }
  });
  xhr.addEventListener('load', async () => {
    if (xhr.status >= 200 && xhr.status < 300) {
      const name = responseFileName(xhr.getResponseHeader('Content-Disposition')) || 'propel-export.xlsx';
      const url = URL.createObjectURL(xhr.response);
      const link = document.createElement('a');
      link.href = url;
      link.download = name;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 30_000);
      const merged = xhr.getResponseHeader('X-Propel-Merged-Rows');
      const filtered = xhr.getResponseHeader('X-Propel-Filtered-Rows');
      setBusy(false, 'success');
      statusTitle.textContent = 'Excel 已生成并开始下载';
      statusDetail.textContent = merged ? `合并 ${merged} 行，FilteredFrames 保留 ${filtered} 行。` : '可以继续调整参数并再次导出。';
      return;
    }
    let message = `请求失败（${xhr.status}）`;
    try {
      const payload = JSON.parse(await xhr.response.text());
      if (payload.error) message = payload.error;
    } catch { /* response was not JSON */ }
    setBusy(false, 'error');
    statusTitle.textContent = '未能生成工作簿';
    statusDetail.textContent = message;
  });
  xhr.addEventListener('error', () => {
    setBusy(false, 'error');
    statusTitle.textContent = '网络连接中断';
    statusDetail.textContent = '上传未完成，请检查网络后重试。';
  });
  xhr.send(body);
});

function setBusy(busy, outcome) {
  submitButton.disabled = busy;
  progressWrap.hidden = !busy;
  document.querySelector('.button-label').textContent = busy ? '正在处理…' : '生成并下载 Excel';
  if (busy) {
    statusTitle.textContent = '正在上传文件';
    statusDetail.textContent = '请保持当前页面打开。';
    progressText.textContent = '准备上传';
    progressBar.classList.remove('indeterminate');
    progressBar.style.width = '0';
  } else {
    progressBar.classList.remove('indeterminate');
    progressBar.style.width = outcome === 'success' ? '100%' : '0';
  }
}

function responseFileName(header) {
  if (!header) return null;
  const extended = header.match(/filename\*=UTF-8''([^;]+)/i);
  if (extended) {
    try { return decodeURIComponent(extended[1]); } catch { /* fall through */ }
  }
  const simple = header.match(/filename="([^"]+)"/i);
  return simple ? simple[1] : null;
}

loadServerCapabilities();
