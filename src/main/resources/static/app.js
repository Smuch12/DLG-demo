const form = document.getElementById('dlg-form');
const runButton = document.getElementById('runButton');
const statusPill = document.getElementById('status-pill');
const sigmaRange = document.getElementById('sigmaRange');
const sigmaInput = document.getElementById('sigmaInput');
const targetImage = document.getElementById('targetImage');
const targetPlaceholder = document.getElementById('targetPlaceholder');
const reconstructionImage = document.getElementById('reconstructionImage');
const iterationMetric = document.getElementById('iterationMetric');
const lossMetric = document.getElementById('lossMetric');
const mseMetric = document.getElementById('mseMetric');
const psnrMetric = document.getElementById('psnrMetric');
const timeline = document.getElementById('timeline');
const log = document.getElementById('log');
const chart = document.getElementById('lossChart');
const jobIdLabel = document.getElementById('jobId');
const sampleSelect = document.getElementById('sampleId');

let eventSource = null;
let currentJob = null;
let previewRequestId = 0;

function setSigma(value) {
  const normalized = Number(value);
  sigmaInput.value = Number.isFinite(normalized) ? normalized : 0;
  sigmaRange.value = Math.min(1, Math.max(0, Number(sigmaInput.value)));
}

sigmaRange.addEventListener('input', () => setSigma(sigmaRange.value));
sigmaInput.addEventListener('input', () => {
  sigmaRange.value = Math.min(1, Math.max(0, Number(sigmaInput.value || 0)));
});

document.querySelectorAll('[data-sigma]').forEach(button => {
  button.addEventListener('click', () => setSigma(button.dataset.sigma));
});

sampleSelect.addEventListener('change', () => {
  loadSamplePreview();
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  closeEvents();
  resetRunView();

  const payload = new FormData(form);
  payload.set('sigma', sigmaInput.value || '0');

  runButton.disabled = true;
  setStatus('Queued');
  appendLog('Creating DLG job...');

  try {
    const response = await fetch('/api/dlg/jobs', {
      method: 'POST',
      body: payload,
    });
    if (!response.ok) {
      throw new Error(await response.text());
    }
    currentJob = await response.json();
    handleSnapshot(currentJob);
    connectEvents(currentJob.id);
  } catch (error) {
    setStatus('Failed');
    runButton.disabled = false;
    appendLog(error.message || 'Failed to create job.');
  }
});

function connectEvents(id) {
  eventSource = new EventSource(`/api/dlg/jobs/${id}/events`);

  eventSource.addEventListener('snapshot', event => handleSnapshot(JSON.parse(event.data)));
  eventSource.addEventListener('status', event => handleSnapshot(JSON.parse(event.data)));
  eventSource.addEventListener('target', event => {
    const snapshot = JSON.parse(event.data);
    handleSnapshot(snapshot);
    loadTarget(snapshot);
  });
  eventSource.addEventListener('progress', event => {
    const snapshot = JSON.parse(event.data);
    handleSnapshot(snapshot);
    loadTarget(snapshot);
    if (snapshot.latestFrameUrl) {
      reconstructionImage.src = cacheBust(snapshot.latestFrameUrl);
    }
  });
  eventSource.addEventListener('done', event => {
    const snapshot = JSON.parse(event.data);
    handleSnapshot(snapshot);
    setStatus('Succeeded');
    appendLog('DLG run complete.');
    runButton.disabled = false;
    closeEvents();
  });
  eventSource.addEventListener('error', event => {
    if (event.data) {
      handleSnapshot(JSON.parse(event.data));
      appendLog('DLG run failed.');
      setStatus('Failed');
      runButton.disabled = false;
    }
    closeEvents();
  });
  eventSource.addEventListener('message', event => {
    try {
      const payload = JSON.parse(event.data);
      if (payload.message) appendLog(payload.message);
    } catch {
      appendLog(event.data);
    }
  });
}

function closeEvents() {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
}

function resetRunView() {
  currentJob = null;
  timeline.innerHTML = '';
  reconstructionImage.removeAttribute('src');
  log.textContent = '';
  drawChart([]);
}

function handleSnapshot(snapshot) {
  currentJob = snapshot;
  setStatus(snapshot.status);
  jobIdLabel.textContent = snapshot.id ? `Job ${snapshot.id.slice(0, 8)}` : 'No job';
  iterationMetric.textContent = `${snapshot.currentIteration || 0} / ${snapshot.totalIterations || 300}`;
  lossMetric.textContent = formatNumber(snapshot.loss);
  mseMetric.textContent = formatNumber(snapshot.mse);
  psnrMetric.textContent = snapshot.psnr == null ? '-' : `${Number(snapshot.psnr).toFixed(2)} dB`;

  if (snapshot.latestFrameUrl) {
    reconstructionImage.src = cacheBust(snapshot.latestFrameUrl);
  }

  renderTimeline(snapshot.progress || []);
  drawChart(snapshot.progress || []);
}

function loadTarget(snapshot) {
  if (!snapshot || !snapshot.targetUrl) return;
  if (!targetImage.src || !targetImage.src.includes(snapshot.id)) {
    targetPlaceholder.hidden = true;
    targetImage.src = cacheBust(snapshot.targetUrl);
  }
}

function renderTimeline(points) {
  timeline.innerHTML = '';
  points.slice(-36).forEach(point => {
    if (!point.frameUrl) return;
    const button = document.createElement('button');
    button.type = 'button';
    button.title = `Iteration ${point.iteration}`;
    button.innerHTML = `
      <img src="${cacheBust(point.frameUrl)}" alt="Iteration ${point.iteration}">
      <span>${point.iteration}</span>
    `;
    button.addEventListener('click', () => {
      reconstructionImage.src = cacheBust(point.frameUrl);
    });
    timeline.appendChild(button);
  });
}

function drawChart(points) {
  const context = chart.getContext('2d');
  const rect = chart.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  chart.width = Math.max(1, Math.floor(rect.width * ratio));
  chart.height = Math.max(180, Math.floor(220 * ratio));
  context.scale(ratio, ratio);

  const width = rect.width;
  const height = 220;
  const padding = {
    top: 18,
    right: 18,
    bottom: 46,
    left: 58,
  };
  const plotWidth = Math.max(1, width - padding.left - padding.right);
  const plotHeight = height - padding.top - padding.bottom;

  context.clearRect(0, 0, width, height);
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, width, height);
  context.strokeStyle = '#d7dee4';
  context.lineWidth = 1;

  for (let i = 0; i <= 4; i += 1) {
    const y = padding.top + (i / 4) * plotHeight;
    context.beginPath();
    context.moveTo(padding.left, y);
    context.lineTo(width - padding.right, y);
    context.stroke();
  }

  context.strokeStyle = '#46535f';
  context.beginPath();
  context.moveTo(padding.left, padding.top);
  context.lineTo(padding.left, padding.top + plotHeight);
  context.lineTo(width - padding.right, padding.top + plotHeight);
  context.stroke();

  context.fillStyle = '#46535f';
  context.font = '12px system-ui, sans-serif';
  context.textAlign = 'center';
  context.fillText('DLG iteration', padding.left + plotWidth / 2, height - 12);

  context.save();
  context.translate(15, padding.top + plotHeight / 2);
  context.rotate(-Math.PI / 2);
  context.fillText('Mean squared error', 0, 0);
  context.restore();

  const usable = points.filter(point => point.mse != null);
  if (usable.length < 2) {
    context.fillStyle = '#73808c';
    context.font = '13px system-ui, sans-serif';
    context.textAlign = 'left';
    context.fillText('MSE chart will update as frames arrive', padding.left + 8, padding.top + 22);
    return;
  }

  const values = usable.map(point => Number(point.mse));
  const max = Math.max(...values);
  const min = Math.min(...values);
  const spread = Math.max(0.000001, max - min);

  context.strokeStyle = '#16817a';
  context.lineWidth = 2;
  context.beginPath();
  usable.forEach((point, index) => {
    const x = padding.left + (index / (usable.length - 1)) * plotWidth;
    const y = padding.top + plotHeight - ((Number(point.mse) - min) / spread) * plotHeight;
    if (index === 0) context.moveTo(x, y);
    else context.lineTo(x, y);
  });
  context.stroke();

  context.fillStyle = '#697783';
  context.font = '11px system-ui, sans-serif';
  context.textAlign = 'right';
  context.fillText(max.toFixed(4), padding.left - 8, padding.top + 4);
  context.fillText(min.toFixed(4), padding.left - 8, padding.top + plotHeight);
  context.textAlign = 'center';
  context.fillText(String(usable[0].iteration), padding.left, padding.top + plotHeight + 18);
  context.fillText(String(usable[usable.length - 1].iteration), padding.left + plotWidth, padding.top + plotHeight + 18);
}

function appendLog(message) {
  const time = new Date().toLocaleTimeString();
  log.textContent += `[${time}] ${message}\n`;
  log.scrollTop = log.scrollHeight;
}

function setStatus(status) {
  statusPill.textContent = status || 'Idle';
  statusPill.dataset.status = String(status || 'idle').toLowerCase();
}

function formatNumber(value) {
  if (value == null || Number.isNaN(Number(value))) return '-';
  const number = Number(value);
  if (number === 0) return '0';
  if (Math.abs(number) < 0.001) return number.toExponential(2);
  return number.toFixed(4);
}

function cacheBust(url) {
  const separator = url.includes('?') ? '&' : '?';
  return `${url}${separator}t=${Date.now()}`;
}

window.addEventListener('resize', () => {
  drawChart(currentJob?.progress || []);
});

function loadSamplePreview() {
  const requestId = ++previewRequestId;
  loadSamplePreviewAttempt(requestId, 0);
}

function loadSamplePreviewAttempt(requestId, attempt) {
  if (requestId !== previewRequestId) return;

  targetPlaceholder.hidden = false;
  targetPlaceholder.textContent = attempt === 0 ? 'Loading target...' : 'Retrying target preview...';
  targetImage.removeAttribute('src');
  const sampleId = encodeURIComponent(sampleSelect.value);
  targetImage.dataset.previewRequestId = String(requestId);
  targetImage.dataset.previewAttempt = String(attempt);
  targetImage.src = cacheBust(`/api/dlg/samples/preview?sampleId=${sampleId}`);
}

targetImage.addEventListener('load', () => {
  targetPlaceholder.hidden = true;
});

targetImage.addEventListener('error', () => {
  const requestId = Number(targetImage.dataset.previewRequestId || 0);
  const attempt = Number(targetImage.dataset.previewAttempt || 0);
  if (requestId === previewRequestId && attempt < 3) {
    window.setTimeout(() => loadSamplePreviewAttempt(requestId, attempt + 1), 700 * (attempt + 1));
    return;
  }
  targetPlaceholder.hidden = false;
  targetPlaceholder.textContent = 'Preview unavailable';
  appendLog('Could not load the target preview. You can still start the job; the target will appear once the worker starts.');
});

loadSamplePreview();
drawChart([]);
