import { useState, useRef, useCallback } from 'react';
import { ChevronLeft, ChevronRight, Upload, FileText } from 'lucide-react';
import { useMolStore } from '@/store/useMolStore';
import { parseMolecule } from '@/modules/molecule-parser';
import { inferBonds } from '@/modules/bond-calculator';

const panelClass =
  'bg-[rgba(20,20,40,0.85)] backdrop-blur-md border border-white/10 rounded-lg text-white/90 font-["DM_Sans"]';

export default function FilePanel() {
  const {
    metadata,
    atomCount,
    bondCount,
    models,
    currentModel,
    setMolecule,
    setCurrentModel,
    setIsLoading,
  } = useMolStore();

  const [collapsed, setCollapsed] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFile = useCallback(
    (file: File) => {
      setIsLoading(true);
      const reader = new FileReader();
      reader.onload = (e) => {
        const content = e.target?.result as string;
        if (!content) {
          setIsLoading(false);
          return;
        }
        const result = parseMolecule(content, file.name);
        if (result.ok) {
          const parsed = result.value;
          if (parsed.bonds.length === 0) {
            parsed.bonds = inferBonds(parsed.atoms);
          }
          setMolecule(parsed);
        }
        setIsLoading(false);
      };
      reader.onerror = () => setIsLoading(false);
      reader.readAsText(file);
    },
    [setMolecule, setIsLoading],
  );

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setDragOver(false);
      const file = e.dataTransfer.files[0];
      if (file) handleFile(file);
    },
    [handleFile],
  );

  const onDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  }, []);

  const onDragLeave = useCallback(() => setDragOver(false), []);

  const onFileChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (file) handleFile(file);
      if (fileInputRef.current) fileInputRef.current.value = '';
    },
    [handleFile],
  );

  if (collapsed) {
    return (
      <div className={`${panelClass} fixed left-4 top-4 z-20 p-2`}>
        <button
          onClick={() => setCollapsed(false)}
          className="p-1.5 hover:bg-white/10 rounded transition-colors"
        >
          <ChevronRight size={18} />
        </button>
      </div>
    );
  }

  return (
    <div className={`${panelClass} fixed left-4 top-4 z-20 w-72 p-4 space-y-4`}>
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold tracking-wide uppercase">File</h2>
        <button
          onClick={() => setCollapsed(true)}
          className="p-1.5 hover:bg-white/10 rounded transition-colors"
        >
          <ChevronLeft size={18} />
        </button>
      </div>

      <div
        onDrop={onDrop}
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        onClick={() => fileInputRef.current?.click()}
        className={`flex flex-col items-center justify-center gap-2 py-6 border-2 border-dashed rounded-lg cursor-pointer transition-colors ${
          dragOver
            ? 'border-[#00d4aa] bg-[#00d4aa]/10'
            : 'border-white/20 hover:border-white/40'
        }`}
      >
        <Upload size={24} className="text-white/50" />
        <span className="text-xs text-white/50">Drop PDB / SDF / XYZ</span>
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept=".pdb,.sdf,.xyz,.ent,.mol"
        onChange={onFileChange}
        className="hidden"
      />

      {metadata && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 text-sm">
            <FileText size={14} className="text-[#00d4aa]" />
            <span className="truncate">{metadata.fileName}</span>
          </div>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-white/60">
            <span>Atoms</span>
            <span className="text-white/90">{atomCount}</span>
            <span>Bonds</span>
            <span className="text-white/90">{bondCount}</span>
            <span>Format</span>
            <span className="text-white/90 uppercase">{metadata.format}</span>
          </div>

          {models.length > 1 && (
            <div className="pt-2">
              <label className="text-xs text-white/60 block mb-1">Model</label>
              <select
                value={currentModel}
                onChange={(e) => setCurrentModel(Number(e.target.value))}
                className="w-full bg-white/5 border border-white/10 rounded px-2 py-1.5 text-sm text-white/90 focus:outline-none focus:border-[#00d4aa]"
              >
                {models.map((_, i) => (
                  <option key={i} value={i} className="bg-[#141428]">
                    Model {i + 1}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
