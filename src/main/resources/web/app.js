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
const targetCurrency = document.querySelector('#target-currency');
const currencyRateList = document.querySelector('#currency-rate-list');
const currencyRateRowTemplate = document.querySelector('#currency-rate-row-template');
const addSourceCurrency = document.querySelector('#add-source-currency');
const currencyRates = document.querySelector('#currency-rates');
const outputName = document.querySelector('#output-name');
const chooseSaveLocation = document.querySelector('#choose-save-location');
const saveLocationSummary = document.querySelector('#save-location-summary');
const feishuAuthPanel = document.querySelector('#feishu-auth-panel');
const feishuAccessToken = document.querySelector('#feishu-access-token');
const feishuAppId = document.querySelector('#feishu-app-id');
const feishuAppSecret = document.querySelector('#feishu-app-secret');
const saveFeishuAuth = document.querySelector('#save-feishu-auth');
const feishuAuthStatus = document.querySelector('#feishu-auth-status');

let selectedFiles = [];
let maxUploadBytes = Infinity;
let saveFileHandle = null;
let exportServiceAvailable = false;
let exportInProgress = false;
let desktopMode = false;
let healthRetryTimer = null;
let healthAttempt = 0;
const MAX_AUTOMATIC_HEALTH_ATTEMPTS = 6;

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

function normalizeCurrencyInput(input) {
  input.value = input.value.toUpperCase().replace(/[^A-Z]/g, '').slice(0, 3);
  return input.value;
}

function currencyRows() {
  return [...currencyRateList.querySelectorAll('.currency-rate-row')];
}

function updateCurrencyRemoveButtons() {
  const rows = currencyRows();
  for (const row of rows) {
    row.querySelector('.remove-currency-button').disabled = rows.length === 1;
  }
}

function syncCurrencyRateRows() {
  const target = normalizeCurrencyInput(targetCurrency);
  for (const row of currencyRows()) {
    const sourceInput = row.querySelector('.source-currency-input');
    const rateInput = row.querySelector('.exchange-rate-input');
    const rateField = row.querySelector('.rate-field');
    const source = normalizeCurrencyInput(sourceInput);
    const identity = source.length === 3 && target.length === 3 && source === target;
    rateField.classList.toggle('disabled', identity);
    rateInput.disabled = identity;
    rateInput.required = !identity;
    if (identity) {
      rateInput.value = '1';
      rateInput.dataset.identityRate = 'true';
    } else if (rateInput.dataset.identityRate === 'true') {
      rateInput.value = '';
      delete rateInput.dataset.identityRate;
    }
    rateField.querySelector('span').textContent =
      `Rate to ${target.length === 3 ? target : 'target'}${identity ? ' (fixed at 1)' : ' *'}`;
  }
}

function addCurrencyRateRow(source = '', rate = '') {
  const row = currencyRateRowTemplate.content.firstElementChild.cloneNode(true);
  const sourceInput = row.querySelector('.source-currency-input');
  const rateInput = row.querySelector('.exchange-rate-input');
  const removeButton = row.querySelector('.remove-currency-button');
  sourceInput.value = source;
  rateInput.value = rate;
  sourceInput.addEventListener('input', () => {
    sourceInput.setCustomValidity('');
    syncCurrencyRateRows();
  });
  rateInput.addEventListener('input', () => rateInput.setCustomValidity(''));
  removeButton.addEventListener('click', () => {
    row.remove();
    updateCurrencyRemoveButtons();
    syncCurrencyRatesField();
  });
  currencyRateList.append(row);
  updateCurrencyRemoveButtons();
  syncCurrencyRateRows();
}

function syncCurrencyRatesField() {
  const seen = new Set();
  const mappings = [];
  let valid = true;
  for (const row of currencyRows()) {
    const sourceInput = row.querySelector('.source-currency-input');
    const rateInput = row.querySelector('.exchange-rate-input');
    const source = normalizeCurrencyInput(sourceInput);
    sourceInput.setCustomValidity('');
    rateInput.setCustomValidity('');
    if (source.length === 3 && seen.has(source)) {
      sourceInput.setCustomValidity(`Source currency ${source} is listed more than once.`);
      valid = false;
      continue;
    }
    if (source.length === 3) seen.add(source);
    const rate = Number(rateInput.value);
    if (source.length === 3 && Number.isFinite(rate) && rate > 0) {
      mappings.push(`${source}=${rateInput.value}`);
    }
  }
  currencyRates.value = mappings.join(',');
  return valid;
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

targetCurrency.addEventListener('input', syncCurrencyRateRows);
addSourceCurrency.addEventListener('click', () => addCurrencyRateRow());
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

async function loadServerCapabilities(manualRetry = false) {
  if (healthRetryTimer) {
    clearTimeout(healthRetryTimer);
    healthRetryTimer = null;
  }
  if (manualRetry) healthAttempt = 0;
  healthAttempt += 1;
  submitButton.type = 'submit';
  submitButton.disabled = true;
  document.querySelector('.button-label').textContent = 'Connecting...';
  statusTitle.textContent = 'Connecting to export service';
  statusDetail.textContent = 'Checking the Java export backend. A sleeping Cloudflare Container may take a moment to start.';
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20_000);
    const response = await fetch('/api/health', { cache: 'no-store', signal: controller.signal });
    clearTimeout(timeout);
    if (!response.ok) throw new Error(`health check failed (${response.status})`);
    const health = await response.json();
    exportServiceAvailable = true;
    exportInProgress = false;
    desktopMode = health.desktopMode === true;
    feishuAuthPanel.hidden = !desktopMode;
    healthAttempt = 0;
    submitButton.type = 'submit';
    submitButton.disabled = false;
    document.querySelector('.button-label').textContent = 'Generate Excel';
    progressWrap.hidden = true;
    progressText.textContent = 'Ready';
    statusTitle.textContent = 'Export service is ready';
    statusDetail.textContent = 'Upload the frame files and generate the workbook with the original Propel Java workflow.';
    maxUploadBytes = health.maxUploadBytes;
    maxUploadLabel.textContent = `Limit ${formatBytes(maxUploadBytes)} per request`;
    const help = document.querySelector('#remote-images-help');
    if (!health.allowRemoteImages) {
      help.textContent = 'External image downloading is disabled. PICS still uses the latest Proposal and supply-matrix matching logic, preserves matched source links, and inserts placeholders when needed.';
    } else if (health.feishuAuthConfigured) {
      help.textContent = 'Feishu authentication is configured. The backend normalizes Proposal venue types, matches the supply matrix, downloads folder images into meta/images, and embeds them in PICS.';
    } else {
      help.textContent = desktopMode
        ? 'The latest PICS matching logic is active. Enter Feishu credentials below so protected folder images can be downloaded into meta/images and embedded in PICS.'
        : 'The latest PICS matching logic is active and supply links are preserved. Protected Feishu images require PROPEL_FEISHU_ACCESS_TOKEN or Feishu app credentials for downloading.';
    }
  } catch {
    exportServiceAvailable = false;
    exportInProgress = false;
    progressWrap.hidden = true;
    progressBar.classList.remove('indeterminate');
    progressBar.style.width = '0';
    progressText.textContent = 'Unavailable';
    if (healthAttempt < MAX_AUTOMATIC_HEALTH_ATTEMPTS) {
      const seconds = 5;
      submitButton.disabled = true;
      document.querySelector('.button-label').textContent = 'Connecting...';
      maxUploadLabel.textContent = 'Starting export service';
      statusTitle.textContent = 'Java export service is starting';
      statusDetail.textContent = `Retrying automatically in ${seconds} seconds (${healthAttempt}/${MAX_AUTOMATIC_HEALTH_ATTEMPTS}).`;
      healthRetryTimer = setTimeout(() => loadServerCapabilities(), seconds * 1000);
      return;
    }
    submitButton.type = 'button';
    submitButton.disabled = false;
    document.querySelector('.button-label').textContent = 'Retry connection';
    maxUploadLabel.textContent = 'Backend not connected';
    statusTitle.textContent = 'Export service is unavailable';
    statusDetail.textContent = 'Retry the connection. If it still fails, open the Workers deployment URL: a static Cloudflare Pages deployment cannot run the Java export backend.';
  }
}

saveFeishuAuth.addEventListener('click', async () => {
  if (!desktopMode) return;
  const body = new URLSearchParams();
  body.set('accessToken', feishuAccessToken.value.trim());
  body.set('appId', feishuAppId.value.trim());
  body.set('appSecret', feishuAppSecret.value.trim());
  saveFeishuAuth.disabled = true;
  feishuAuthStatus.textContent = 'Applying credentials...';
  try {
    const response = await fetch('/api/feishu-auth', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
        'X-Propel-Desktop': '1',
      },
      body,
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || `Request failed (${response.status})`);
    feishuAccessToken.value = '';
    feishuAppSecret.value = '';
    feishuAuthStatus.textContent = 'Credentials are active for this app session.';
    await loadServerCapabilities(true);
  } catch (error) {
    feishuAuthStatus.textContent = error instanceof Error ? error.message : 'Could not apply credentials.';
  } finally {
    saveFeishuAuth.disabled = false;
  }
});

submitButton.addEventListener('click', event => {
  if (!exportServiceAvailable && submitButton.type === 'button') {
    event.preventDefault();
    loadServerCapabilities(true);
  }
});

form.addEventListener('submit', event => {
  event.preventDefault();
  fileError.textContent = '';
  syncCurrencyRateRows();
  const currencyMappingsValid = syncCurrencyRatesField();
  if (!exportServiceAvailable) {
    statusTitle.textContent = 'Export service is unavailable';
    statusDetail.textContent = 'Cannot run the original Propel export flow until /api/health is served by the Java backend.';
    return;
  }
  if (!form.reportValidity() || !currencyMappingsValid) return;
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
  body.set('targetCurrency', normalizeCurrencyInput(targetCurrency));
  body.set('currencyRates', currencyRates.value);
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
  exportInProgress = busy;
  submitButton.disabled = busy || !exportServiceAvailable;
  progressWrap.hidden = !busy;
  document.querySelector('.button-label').textContent = exportServiceAvailable
    ? (busy ? 'Processing...' : 'Generate Excel')
    : 'Service unavailable';
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

addCurrencyRateRow();
loadServerCapabilities();
