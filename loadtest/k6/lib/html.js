export function extractHiddenValue(html, id) {
  const pattern = new RegExp(`id=["']${escapeRegExp(id)}["'][^>]*value=["']([^"']+)["']`);
  const match = String(html || '').match(pattern);
  return match ? match[1] : null;
}

export function findDocumentRow(html, fileName) {
  const rows = String(html || '').match(/<tr\b[^>]*data-status=["'][^"']+["'][^>]*>[\s\S]*?<\/tr>/gi) || [];
  const encodedName = escapeHtml(fileName);
  const row = rows.find(candidate => candidate.includes(fileName) || candidate.includes(encodedName));
  if (!row) {
    return null;
  }

  const status = row.match(/data-status=["']([^"']+)["']/i);
  const documentId = row.match(/data-doc-id=["'](\d+)["']/i);
  return {
    status: status ? status[1] : null,
    documentId: documentId ? documentId[1] : null,
  };
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
