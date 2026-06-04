import axios from 'axios';

const api = axios.create({ baseURL: '/api' });

export async function uploadPdb(file, name) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('name', name || file.name);
  formData.append('projectId', '1');
  const res = await api.post('/structures/upload', formData);
  return res.data;
}

export async function getStructure(id) {
  const res = await api.get(`/structures/${id}`);
  return res.data;
}

export async function listStructures() {
  const res = await api.get('/structures');
  return res.data;
}

export async function calculateDistance(id, atom1, atom2) {
  const res = await api.get(`/${id}/distance`, { params: { atom1, atom2 } });
  return res.data;
}

export async function calculateAngle(id, atom1, atom2, atom3) {
  const res = await api.get(`/${id}/angle`, { params: { atom1, atom2, atom3 } });
  return res.data;
}

export async function analyzeInteractions(id, chainId, resSeq, cutoff = 5.0) {
  const res = await api.get(`/${id}/interactions`, { params: { chainId, resSeq, cutoff } });
  return res.data;
}

export async function alignStructures(id1, id2) {
  const res = await api.post('/structures/align', null, { params: { id1, id2 } });
  return res.data;
}

export async function getElectrostaticSurface(id) {
  const res = await api.get(`/${id}/electrostatic-surface`);
  return res.data;
}

export async function batchAnalysis(ids) {
  const res = await api.post('/structures/batch-analysis', ids);
  return res.data;
}

export async function createAnnotation(dto) {
  const res = await api.post('/collaboration/annotations', dto);
  return res.data;
}

export async function getAnnotations(structureId) {
  const res = await api.get(`/collaboration/annotations/${structureId}`);
  return res.data;
}

export async function updateAnnotation(id, dto) {
  const res = await api.put(`/collaboration/annotations/${id}`, dto);
  return res.data;
}

export async function deleteAnnotation(id) {
  await api.delete(`/collaboration/annotations/${id}`);
}

export async function addComment(structureId, content, x, y, z, userId = 1) {
  const res = await api.post('/collaboration/comments', null, { params: { structureId, content, x, y, z, userId } });
  return res.data;
}

export async function getComments(structureId) {
  const res = await api.get(`/collaboration/comments/${structureId}`);
  return res.data;
}

export async function createSnapshot(dto) {
  const res = await api.post('/collaboration/snapshots', dto);
  return res.data;
}

export async function getSnapshot(shortId) {
  const res = await api.get(`/collaboration/snapshots/${shortId}`);
  return res.data;
}
