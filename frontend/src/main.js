import { MolecularViewer } from './components/MolecularViewer.js';
import * as api from './services/api.js';

let viewer;
let currentStructureId = null;
let currentStructureData = null;
let isCommentMode = false;

async function init() {
  const canvas = document.getElementById('viewer-canvas');
  viewer = new MolecularViewer(canvas);

  viewer.onAtomClick = (atom) => {
    showAtomInfo(atom);
  };

  viewer.onBackgroundClick = () => {
    document.getElementById('atom-info-panel').style.display = 'none';
  };

  setupUpload();
  setupAnalysisToolSelector();
  checkSnapshotUrl();
}

function setupUpload() {
  const zone = document.getElementById('upload-zone');
  const input = document.getElementById('file-input');

  zone.addEventListener('click', () => input.click());

  zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('dragover'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('dragover'));
  zone.addEventListener('drop', (e) => {
    e.preventDefault();
    zone.classList.remove('dragover');
    if (e.dataTransfer.files.length > 0) handleFile(e.dataTransfer.files[0]);
  });

  input.addEventListener('change', (e) => {
    if (e.target.files.length > 0) handleFile(e.target.files[0]);
  });
}

async function handleFile(file) {
  try {
    const result = await api.uploadPdb(file, file.name.replace('.pdb', ''));
    currentStructureId = result.id;
    loadStructure(result.id);
  } catch (err) {
    console.error('Upload failed:', err);
    alert('Upload failed: ' + err.message);
  }
}

async function loadStructure(id) {
  try {
    const data = await api.getStructure(id);
    currentStructureData = data;
    currentStructureId = id;
    viewer.loadStructure(data);
    showStructureInfo(data);
    showValidationWarnings(data.warnings);
    loadAnnotations(id);
    loadComments(id);
  } catch (err) {
    console.error('Load failed:', err);
  }
}

function showStructureInfo(data) {
  const panel = document.getElementById('structure-info-panel');
  panel.style.display = 'block';
  document.getElementById('structure-info').innerHTML = `
    <div class="info-card"><div class="label">PDB ID</div><div class="value">${data.pdbId || 'N/A'}</div></div>
    <div class="info-card"><div class="label">Title</div><div class="value" style="font-size:11px;">${data.title || 'N/A'}</div></div>
    <div class="info-card" style="display:flex;gap:12px;">
      <div style="flex:1;"><div class="label">Atoms</div><div class="value">${data.totalAtoms}</div></div>
      <div style="flex:1;"><div class="label">Residues</div><div class="value">${data.totalResidues}</div></div>
      <div style="flex:1;"><div class="label">Bonds</div><div class="value">${data.bonds.length}</div></div>
    </div>
    <div class="info-card"><div class="label">Chains</div><div class="value">${(data.chainIds || []).join(', ')}</div></div>
  `;

  const statsPanel = document.getElementById('stats-panel');
  statsPanel.style.display = 'block';
  document.getElementById('stats-content').innerHTML = `
    <div class="stat"><span>Atoms:</span><span class="num">${data.totalAtoms}</span></div>
    <div class="stat"><span>Residues:</span><span class="num">${data.totalResidues}</span></div>
    <div class="stat"><span>Bonds:</span><span class="num">${data.bonds.length}</span></div>
    <div class="stat"><span>Chains:</span><span class="num">${(data.chainIds || []).length}</span></div>
  `;
}

function showValidationWarnings(warnings) {
  const panel = document.getElementById('validation-panel');
  if (!warnings || warnings.length === 0) { panel.style.display = 'none'; return; }
  panel.style.display = 'block';
  document.getElementById('validation-warnings').innerHTML = warnings.map(w => `
    <div class="warning-item">
      <span class="line">Line ${w.lineNumber}</span> [${w.field}]: ${w.message}
    </div>
  `).join('');
}

function showAtomInfo(atom) {
  const panel = document.getElementById('atom-info-panel');
  panel.style.display = 'block';
  document.getElementById('atom-info').innerHTML = `
    <div class="info-card">
      <div class="label">Atom Name</div><div class="value">${atom.atomName}</div>
    </div>
    <div class="info-card">
      <div class="label">Residue</div><div class="value">${atom.residueName} ${atom.residueSeqNumber} (${atom.chainId})</div>
    </div>
    <div class="info-card">
      <div class="label">Element</div><div class="value">${atom.element}</div>
    </div>
    <div class="info-card">
      <div class="label">Coordinates</div>
      <div class="value" style="font-size:11px;">X: ${atom.x.toFixed(3)}, Y: ${atom.y.toFixed(3)}, Z: ${atom.z.toFixed(3)}</div>
    </div>
    <div class="info-card">
      <div class="label">B-Factor</div><div class="value">${atom.tempFactor.toFixed(2)}</div>
    </div>
    <div class="info-card">
      <div class="label">Serial</div><div class="value">${atom.serialNumber}</div>
    </div>
  `;
}

function setupAnalysisToolSelector() {
  const select = document.getElementById('analysis-tool');
  select.addEventListener('change', () => {
    const params = document.getElementById('tool-params');
    const tool = select.value;
    if (tool === 'distance') {
      params.innerHTML = `
        <div class="input-group"><label>Atom 1 Serial</label><input type="number" id="param-atom1" /></div>
        <div class="input-group"><label>Atom 2 Serial</label><input type="number" id="param-atom2" /></div>
      `;
    } else if (tool === 'angle') {
      params.innerHTML = `
        <div class="input-group"><label>Atom 1 Serial</label><input type="number" id="param-atom1" /></div>
        <div class="input-group"><label>Atom 2 Serial (vertex)</label><input type="number" id="param-atom2" /></div>
        <div class="input-group"><label>Atom 3 Serial</label><input type="number" id="param-atom3" /></div>
      `;
    } else if (tool === 'interactions') {
      params.innerHTML = `
        <div class="input-group"><label>Chain ID</label><input type="text" id="param-chain" value="A" /></div>
        <div class="input-group"><label>Residue Seq</label><input type="number" id="param-resseq" /></div>
        <div class="input-group"><label>Cutoff (Å)</label><input type="number" id="param-cutoff" value="5.0" step="0.5" /></div>
      `;
    }
  });
}

window.runAnalysis = async function () {
  if (!currentStructureId) { alert('No structure loaded'); return; }
  const tool = document.getElementById('analysis-tool').value;
  const resultDiv = document.getElementById('analysis-result');

  try {
    if (tool === 'distance') {
      const a1 = parseInt(document.getElementById('param-atom1').value);
      const a2 = parseInt(document.getElementById('param-atom2').value);
      const result = await api.calculateDistance(currentStructureId, a1, a2);
      resultDiv.innerHTML = `<div class="info-card"><div class="label">Distance</div><div class="value">${result.distance} ${result.unit}</div></div>`;

      const atom1 = currentStructureData.atoms.find(a => a.serialNumber === a1);
      const atom2 = currentStructureData.atoms.find(a => a.serialNumber === a2);
      if (atom1 && atom2) viewer.addDistanceLine(atom1, atom2);

    } else if (tool === 'angle') {
      const a1 = parseInt(document.getElementById('param-atom1').value);
      const a2 = parseInt(document.getElementById('param-atom2').value);
      const a3 = parseInt(document.getElementById('param-atom3').value);
      const result = await api.calculateAngle(currentStructureId, a1, a2, a3);
      resultDiv.innerHTML = `<div class="info-card"><div class="label">Angle</div><div class="value">${result.angle}°</div></div>`;

      const atom1 = currentStructureData.atoms.find(a => a.serialNumber === a1);
      const atom2 = currentStructureData.atoms.find(a => a.serialNumber === a2);
      const atom3 = currentStructureData.atoms.find(a => a.serialNumber === a3);
      if (atom1 && atom2 && atom3) viewer.addAngleArc(atom1, atom2, atom3);

    } else if (tool === 'interactions') {
      const chainId = document.getElementById('param-chain').value;
      const resSeq = parseInt(document.getElementById('param-resseq').value);
      const cutoff = parseFloat(document.getElementById('param-cutoff').value);
      const result = await api.analyzeInteractions(currentStructureId, chainId, resSeq, cutoff);
      resultDiv.innerHTML = `
        <div class="info-card"><div class="label">Center</div><div class="value">${result.centerResidue} ${result.centerResSeq} (${result.centerChain})</div></div>
        ${result.interactions.map(i => `
          <div class="info-card">
            <span class="tag tag-${i.type === 'hydrogen_bond' ? 'hbond' : i.type === 'hydrophobic' ? 'hydrophobic' : i.type === 'salt_bridge' ? 'saltbridge' : 'pipistack'}">${i.type.replace('_', ' ')}</span>
            <div style="margin-top:4px;">${i.residue} ${i.resSeq} (${i.chain}) — ${i.distance} Å</div>
            <div style="font-size:10px;color:#64748b;">${i.details}</div>
          </div>
        `).join('')}
      `;
    }
  } catch (err) {
    resultDiv.innerHTML = `<div class="info-card" style="border-color:#ef4444;">Error: ${err.message}</div>`;
  }
};

window.runAlignment = async function () {
  const refId = parseInt(document.getElementById('align-ref').value);
  const mobileId = parseInt(document.getElementById('align-mobile').value);
  const resultDiv = document.getElementById('alignment-result');

  try {
    const result = await api.alignStructures(refId, mobileId);
    resultDiv.innerHTML = `
      <div class="info-card"><div class="label">RMSD</div><div class="value" style="font-size:18px;color:#f59e0b;">${result.rmsd.toFixed(3)} Å</div></div>
      <div class="info-card"><div class="label">Aligned Atoms</div><div class="value">${result.alignedAtomCount}</div></div>
    `;

    if (currentStructureId === refId || currentStructureId === mobileId) {
      viewer.showRmsdColoring(result.perResidueRmsd);
    }
  } catch (err) {
    resultDiv.innerHTML = `<div class="info-card" style="border-color:#ef4444;">Error: ${err.message}</div>`;
  }
};

window.setRenderMode = function (mode) {
  viewer.setRenderMode(mode);
  document.querySelectorAll('[data-mode]').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.mode === mode);
  });
};

window.setColorScheme = function (scheme) {
  viewer.setColorScheme(scheme);
  document.querySelectorAll('[data-color]').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.color === scheme);
  });
};

window.toggleDisplay = function (option) {
  const checkbox = document.getElementById('show-' + option);
  viewer.toggleDisplay(option, checkbox.checked);

  if (option === 'electrostatic' && checkbox.checked && currentStructureId) {
    api.getElectrostaticSurface(currentStructureId).then(surface => {
      viewer.loadSurface(surface);
    }).catch(err => console.error('Surface computation failed:', err));
  }
};

window.resetCamera = function () { viewer.resetCamera(); };
window.centerView = function () { viewer.centerView(); };
window.toggleAutoRotate = function () { viewer.toggleAutoRotate(); };

window.captureScreenshot = function () {
  const dataUrl = viewer.captureScreenshot();
  const link = document.createElement('a');
  link.download = 'protein-structure.png';
  link.href = dataUrl;
  link.click();
};

window.addCommentMode = function () {
  isCommentMode = !isCommentMode;
  if (isCommentMode) {
    viewer.canvas.style.cursor = 'crosshair';
    viewer.canvas.addEventListener('click', handleCommentClick);
  } else {
    viewer.canvas.style.cursor = '';
    viewer.canvas.removeEventListener('click', handleCommentClick);
  }
};

async function handleCommentClick(event) {
  if (!isCommentMode) return;
  const pos = viewer.getWorldPositionFromScreen(
    event.clientX - viewer.canvas.getBoundingClientRect().left,
    event.clientY - viewer.canvas.getBoundingClientRect().top
  );

  const content = prompt('Enter comment:');
  if (content && currentStructureId) {
    const comment = await api.addComment(currentStructureId, content, pos.x, pos.y, pos.z);
    viewer.addCommentMarker(pos.x, pos.y, pos.z, comment.id);
  }
  isCommentMode = false;
  viewer.canvas.style.cursor = '';
  viewer.canvas.removeEventListener('click', handleCommentClick);
}

window.addAnnotation = async function () {
  if (!currentStructureId) { alert('No structure loaded'); return; }

  const type = document.getElementById('ann-type').value;
  const label = document.getElementById('ann-label').value;
  const desc = document.getElementById('ann-desc').value;

  if (!label) { alert('Please enter a label'); return; }

  const center = viewer.controls.target;
  const dto = {
    structureId: currentStructureId,
    type, label,
    description: desc,
    positionX: center.x,
    positionY: center.y + 3,
    positionZ: center.z,
    color: type === 'domain' ? '#4CAF50' : type === 'mutation' ? '#FF5722' : type === 'pocket' ? '#2196F3' : '#FFD700',
    visible: true,
  };

  const annotation = await api.createAnnotation(dto);
  viewer.addAnnotationMarker(annotation);
  loadAnnotations(currentStructureId);
};

async function loadAnnotations(structureId) {
  const annotations = await api.getAnnotations(structureId);
  viewer.clearAnnotations();
  for (const ann of annotations) {
    viewer.addAnnotationMarker(ann);
  }

  const list = document.getElementById('annotation-list');
  list.innerHTML = annotations.map(ann => `
    <div class="info-card" style="display:flex;justify-content:space-between;align-items:center;">
      <div>
        <div style="font-weight:600;font-size:12px;">${ann.label}</div>
        <div style="font-size:10px;color:#64748b;">${ann.type}</div>
      </div>
      <button class="btn btn-danger" style="width:auto;margin:0;padding:3px 8px;font-size:10px;" onclick="removeAnnotation(${ann.id})">✕</button>
    </div>
  `).join('');
}

window.removeAnnotation = async function (id) {
  await api.deleteAnnotation(id);
  if (currentStructureId) loadAnnotations(currentStructureId);
};

async function loadComments(structureId) {
  const comments = await api.getComments(structureId);
  for (const c of comments) {
    viewer.addCommentMarker(c.anchorX, c.anchorY, c.anchorZ, c.id);
  }
}

window.shareSnapshot = async function () {
  if (!currentStructureId) { alert('No structure loaded'); return; }
  const camState = viewer.getCameraState();
  const snapshot = await api.createSnapshot({
    structureId: currentStructureId,
    ...camState,
    renderMode: viewer.renderMode,
    colorScheme: viewer.colorScheme,
  });

  const shareDiv = document.getElementById('share-link');
  const url = `${window.location.origin}${window.location.pathname}?snapshot=${snapshot.shortId}`;
  shareDiv.innerHTML = `
    <div class="info-card">
      <div class="label">Share Link</div>
      <div class="value" style="font-size:10px;word-break:break-all;">${url}</div>
      <button class="btn" style="margin-top:4px;font-size:11px;" onclick="navigator.clipboard.writeText('${url}')">Copy</button>
    </div>
  `;
};

window.runBatchAnalysis = async function () {
  const idsStr = document.getElementById('batch-ids').value;
  const ids = idsStr.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n));
  if (ids.length < 2) { alert('Enter at least 2 structure IDs'); return; }

  const resultDiv = document.getElementById('batch-result');
  resultDiv.innerHTML = '<div class="info-card">Running analysis...</div>';

  try {
    const result = await api.batchAnalysis(ids);

    let html = `
      <div class="info-card"><div class="label">Task ID</div><div class="value">${result.taskId}</div></div>
      <div class="info-card"><div class="label">Disulfide Bonds</div><div class="value">${result.disulfideBonds.length}</div></div>
      <div class="info-card"><div class="label">Glycosylation Sites</div><div class="value">${result.glycosylationSites.length}</div></div>
    `;

    if (result.rmsdMatrix && result.rmsdMatrix.length > 0) {
      html += `<div style="margin-top:8px;font-size:11px;font-weight:600;color:#60a5fa;">RMSD Matrix (Å)</div>`;
      html += `<div class="table-container"><table style="margin-top:4px;"><thead><tr><th></th>`;
      for (const name of result.structureNames) html += `<th style="font-size:10px;">${name.substring(0, 8)}</th>`;
      html += `</tr></thead><tbody>`;
      for (let i = 0; i < result.rmsdMatrix.length; i++) {
        html += `<tr><td style="font-size:10px;font-weight:600;">${result.structureNames[i].substring(0, 8)}</td>`;
        for (let j = 0; j < result.rmsdMatrix[i].length; j++) {
          const val = result.rmsdMatrix[i][j];
          const bg = i === j ? '#1e293b' : val < 0 ? '#374151' : `rgba(${Math.min(255, val * 50)}, ${Math.max(0, 255 - val * 50)}, 0, 0.3)`;
          html += `<td class="heatmap-cell" style="background:${bg};">${val >= 0 ? val.toFixed(2) : '—'}</td>`;
        }
        html += `</tr>`;
      }
      html += `</tbody></table></div>`;
    }

    if (result.bfactorStats) {
      html += `<div style="margin-top:8px;font-size:11px;font-weight:600;color:#60a5fa;">B-Factor Statistics</div>`;
      html += `<div class="table-container"><table style="margin-top:4px;"><thead><tr><th>ID</th><th>Mean</th><th>StdDev</th><th>Min</th><th>Max</th><th>Median</th></tr></thead><tbody>`;
      for (const [id, stats] of Object.entries(result.bfactorStats)) {
        html += `<tr><td>${id}</td><td>${stats.mean.toFixed(2)}</td><td>${stats.stdDev.toFixed(2)}</td><td>${stats.min.toFixed(2)}</td><td>${stats.max.toFixed(2)}</td><td>${stats.median.toFixed(2)}</td></tr>`;
      }
      html += `</tbody></table></div>`;
    }

    resultDiv.innerHTML = html;
  } catch (err) {
    resultDiv.innerHTML = `<div class="info-card" style="border-color:#ef4444;">Error: ${err.message}</div>`;
  }
};

async function checkSnapshotUrl() {
  const params = new URLSearchParams(window.location.search);
  const snapshotId = params.get('snapshot');
  if (snapshotId) {
    try {
      const snapshot = await api.getSnapshot(snapshotId);
      if (snapshot) {
        currentStructureId = snapshot.structureId;
        await loadStructure(snapshot.structureId);
        viewer.setCameraState(snapshot);
        if (snapshot.renderMode) viewer.setRenderMode(snapshot.renderMode);
        if (snapshot.colorScheme) viewer.setColorScheme(snapshot.colorScheme);
      }
    } catch (e) {
      console.error('Failed to load snapshot:', e);
    }
  }
}

init();
