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
const maxUploadLabel = document.querySelector('#upload-limit');
const sourceCurrency = document.querySelector('#source-currency');
const targetCurrency = document.querySelector('#target-currency');
const exchangeRate = document.querySelector('#exchange-rate');
const exchangeRateField = document.querySelector('#exchange-rate-field');
const outputName = document.querySelector('#output-name');
const chooseSaveLocation = document.querySelector('#choose-save-location');
const saveLocationSummary = document.querySelector('#save-location-summary');

let selectedFiles = [];
let maxUploadBytes = Infinity;
let saveFileHandle = null;
let exportServiceAvailable = false;

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
  fileError.textContent = rejected ? `${rejected} unsupported file(s) were ignored.` : '';
  renderFiles();
}

function renderFiles() {
  fileList.replaceChildren();
  const total = selectedFiles.reduce((sum, file) => sum + file.size, 0);
  fileSummary.hidden = selectedFiles.length === 0;
  fileCount.textContent = `${selectedFiles.length} file${selectedFiles.length === 1 ? '' : 's'}`;
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
    remove.setAttribute('aria-label', `Remove ${file.name}`);
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

function syncExchangeRateRequirement() {
  const sameCurrency = sourceCurrency.value && sourceCurrency.value === targetCurrency.value;
  exchangeRateField.classList.toggle('disabled', sameCurrency);
  exchangeRate.required = !sameCurrency;
  exchangeRate.disabled = sameCurrency;
  if (sameCurrency) {
    exchangeRate.value = '1';
  } else if (exchangeRate.value === '1') {
    exchangeRate.value = '';
  }
}

function normalizedOutputName() {
  const raw = outputName.value.trim() || 'viooh-propel-autopilot.xlsx';
  return raw.toLowerCase().endsWith('.xlsx') ? raw : `${raw}.xlsx`;
}

async function chooseSaveFile() {
  if (!('showSaveFilePicker' in window)) {
    saveFileHandle = null;
    saveLocationSummary.textContent = 'This browser cannot choose a save folder. The file will be saved through the default download flow.';
    return;
  }
  try {
    const handle = await window.showSaveFilePicker({
      suggestedName: normalizedOutputName(),
      types: [
        {
          description: 'Excel workbook',
          accept: {
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'],
          },
        },
      ],
    });
    saveFileHandle = handle;
    outputName.value = handle.name;
    saveLocationSummary.textContent = `Selected: ${handle.name}. The browser has stored the folder permission for this export.`;
  } catch (error) {
    if (error && error.name !== 'AbortError') {
      saveFileHandle = null;
      saveLocationSummary.textContent = 'Could not select a save location. The default download flow will be used.';
    }
  }
}

async function saveWorkbookBlob(blob, fallbackName) {
  if (saveFileHandle) {
    try {
      const writable = await saveFileHandle.createWritable();
      await writable.write(blob);
      await writable.close();
      return 'picked-location';
    } catch {
      saveFileHandle = null;
      saveLocationSummary.textContent = 'Could not write to the selected location. The file was downloaded instead.';
    }
  }
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fallbackName;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
  return 'download';
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

sourceCurrency.addEventListener('change', syncExchangeRateRequirement);
targetCurrency.addEventListener('change', syncExchangeRateRequirement);
chooseSaveLocation.addEventListener('click', chooseSaveFile);
outputName.addEventListener('input', () => {
  if (saveFileHandle && outputName.value.trim() !== saveFileHandle.name) {
    saveFileHandle = null;
    saveLocationSummary.textContent = 'File name changed. Choose a save location again if you want to save outside the default download folder.';
  }
});

for (const radio of document.querySelectorAll('input[name="photographyMode"]')) {
  radio.addEventListener('change', () => {
    document.querySelector('#photography-custom-field').hidden = radio.value !== 'custom' || !radio.checked;
  });
}

async function loadServerCapabilities() {
  try {
    const response = await fetch('/api/health', { cache: 'no-store' });
    if (!response.ok) throw new Error('health check failed');
    const health = await response.json();
    exportServiceAvailable = true;
    submitButton.disabled = false;
    maxUploadBytes = health.maxUploadBytes;
    maxUploadLabel.textContent = `Limit ${formatBytes(maxUploadBytes)} per request`;
    const help = document.querySelector('#remote-images-help');
    help.textContent = health.allowRemoteImages
      ? 'The backend matches Proposal Country, MARKET, and Venue type tokens against feishu/supply_matrix.xlsx, downloads Pictures links into meta, and inserts them into PICS.'
      : 'This deployment has disabled external image fetching. Ask the deployment owner to enable it.';
  } catch {
    exportServiceAvailable = false;
    submitButton.disabled = true;
    maxUploadLabel.textContent = 'Service unavailable';
    statusTitle.textContent = 'Export service is unavailable';
    statusDetail.textContent = 'The page is not connected to the Java export backend. Deploy with Cloudflare Workers + Containers, not static Pages only.';
  }
}

form.addEventListener('submit', event => {
  event.preventDefault();
  fileError.textContent = '';
  syncExchangeRateRequirement();
  if (!exportServiceAvailable) {
    statusTitle.textContent = 'Export service is unavailable';
    statusDetail.textContent = 'Cannot run the original Propel export flow until /api/health is served by the Java backend.';
    return;
  }
  if (!form.reportValidity()) return;
  if (selectedFiles.length === 0) {
    fileError.textContent = 'Select at least one frame list file.';
    dropZone.scrollIntoView({ behavior: 'smooth', block: 'center' });
    return;
  }
  if (selectedFiles.every(file => isDetailsFile(file.name))) {
    fileError.textContent = 'Frame-details files cannot be uploaded alone. Add at least one frame list.';
    return;
  }

  const estimatedBytes = selectedFiles.reduce((sum, file) => sum + file.size, 0);
  if (estimatedBytes > maxUploadBytes) {
    fileError.textContent = `Files total ${formatBytes(estimatedBytes)}, above the ${formatBytes(maxUploadBytes)} request limit.`;
    return;
  }

  const body = new FormData(form);
  body.delete('inputFiles');
  for (const file of selectedFiles) body.append('inputFiles', file, file.name);
  body.set('outputName', normalizedOutputName());
  body.set('sourceCurrency', sourceCurrency.value);
  body.set('targetCurrency', targetCurrency.value);
  body.set('exchangeRate', sourceCurrency.value === targetCurrency.value ? '1' : exchangeRate.value);
  body.set('fetchPicsFromLinks', 'true');

  setBusy(true);
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/export');
  xhr.responseType = 'blob';
  xhr.upload.addEventListener('progress', uploadEvent => {
    if (!uploadEvent.lengthComputable) return;
    const percentage = Math.min(100, Math.round(uploadEvent.loaded / uploadEvent.total * 100));
    progressBar.classList.remove('indeterminate');
    progressBar.style.width = `${percentage}%`;
    progressText.textContent = `Uploading ${percentage}%`;
    if (percentage === 100) {
      statusTitle.textContent = 'Server is generating the workbook';
      statusDetail.textContent = 'Large merges, supply matrix image fetching, and Excel formatting may take a few minutes.';
      progressText.textContent = 'Processing';
      progressBar.style.width = '';
      progressBar.classList.add('indeterminate');
    }
  });
  xhr.addEventListener('load', async () => {
    if (xhr.status >= 200 && xhr.status < 300) {
      const name = responseFileName(xhr.getResponseHeader('Content-Disposition')) || 'viooh-propel-autopilot.xlsx';
      const saveMode = await saveWorkbookBlob(xhr.response, name);
      const merged = xhr.getResponseHeader('X-Propel-Merged-Rows');
      const filtered = xhr.getResponseHeader('X-Propel-Filtered-Rows');
      setBusy(false, 'success');
      statusTitle.textContent = 'Excel workbook generated';
      const saveText = saveMode === 'picked-location' ? 'Saved to the selected location.' : 'Saved through the browser download flow.';
      statusDetail.textContent = merged ? `${saveText} Merged ${merged} rows. FilteredFrames keeps ${filtered} rows.` : saveText;
      return;
    }
    let message = `Request failed (${xhr.status})`;
    try {
      const payload = JSON.parse(await xhr.response.text());
      if (payload.error) message = payload.error;
    } catch { /* response was not JSON */ }
    setBusy(false, 'error');
    statusTitle.textContent = 'Workbook generation failed';
    statusDetail.textContent = message;
  });
  xhr.addEventListener('error', () => {
    setBusy(false, 'error');
    statusTitle.textContent = 'Network connection interrupted';
    statusDetail.textContent = 'The upload did not complete. Check the connection and try again.';
  });
  xhr.send(body);
});

function setBusy(busy, outcome) {
  submitButton.disabled = busy;
  progressWrap.hidden = !busy;
  document.querySelector('.button-label').textContent = busy ? 'Processing...' : 'Generate Excel';
  if (busy) {
    statusTitle.textContent = 'Uploading files';
    statusDetail.textContent = 'Keep this page open while the export runs.';
    progressText.textContent = 'Preparing upload';
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

syncExchangeRateRequirement();
loadServerCapabilities();
