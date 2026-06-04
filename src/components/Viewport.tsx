import { useRef, useEffect, useCallback, useState } from 'react';
import { RenderPipeline, type ColorMode } from '@/modules/gpu-render-pipeline';
import { CameraController } from '@/modules/camera-controller';
import { AnnotationLayer } from '@/modules/annotation-layer';
import { useMolStore } from '@/store/useMolStore';
import { vec3Distance, mat4Multiply, mat4Perspective, degToRad, type Vec3, type Mat4 } from '@/utils/math';

function projectPointToScreen(
  point: Vec3,
  viewMatrix: number[],
  projMatrix: number[],
  width: number,
  height: number
): { x: number; y: number; visible: boolean } {
  const v = new Float32Array(4);
  v[0] = point[0];
  v[1] = point[1];
  v[2] = point[2];
  v[3] = 1.0;

  const viewProj = mat4Multiply(viewMatrix as Mat4, projMatrix as Mat4);

  let x = viewProj[0] * v[0] + viewProj[4] * v[1] + viewProj[8] * v[2] + viewProj[12] * v[3];
  let y = viewProj[1] * v[0] + viewProj[5] * v[1] + viewProj[9] * v[2] + viewProj[13] * v[3];
  let z = viewProj[2] * v[0] + viewProj[6] * v[1] + viewProj[10] * v[2] + viewProj[14] * v[3];
  let w = viewProj[3] * v[0] + viewProj[7] * v[1] + viewProj[11] * v[2] + viewProj[15] * v[3];

  x /= w;
  y /= w;
  z /= w;

  return {
    x: (x + 1) * 0.5 * width,
    y: (1 - y) * 0.5 * height,
    visible: w > 0 && z >= -1 && z <= 1,
  };
}

export default function Viewport() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const pipelineRef = useRef<RenderPipeline | null>(null);
  const cameraRef = useRef<CameraController | null>(null);
  const annotationsRef = useRef<AnnotationLayer | null>(null);
  const rafRef = useRef<number>(0);
  const lastTimeRef = useRef<number>(0);
  const frameCountRef = useRef<number>(0);
  const fpsAccumRef = useRef<number>(0);
  const [, forceUpdate] = useState(0);

  const atoms = useMolStore((s) => s.atoms);
  const bonds = useMolStore((s) => s.bonds);
  const isLoading = useMolStore((s) => s.isLoading);
  const isWebGPUAvailable = useMolStore((s) => s.isWebGPUAvailable);
  const atomCount = useMolStore((s) => s.atomCount);
  const bondCount = useMolStore((s) => s.bondCount);
  const fps = useMolStore((s) => s.fps);
  const cameraMode = useMolStore((s) => s.cameraMode);
  const colorMode = useMolStore((s) => s.colorMode);
  const chainIsolation = useMolStore((s) => s.chainIsolation);
  const residueLabelsVisible = useMolStore((s) => s.residueLabelsVisible);
  const backboneRibbonVisible = useMolStore((s) => s.backboneRibbonVisible);
  const hBondIndicatorsVisible = useMolStore((s) => s.hBondIndicatorsVisible);
  const partialChargesVisible = useMolStore((s) => s.partialChargesVisible);
  const bFactorHeatmapVisible = useMolStore((s) => s.bFactorHeatmapVisible);
  const ligandHBondNetworkVisible = useMolStore((s) => s.ligandHBondNetworkVisible);
  const annotationOpacities = useMolStore((s) => s.annotationOpacities);

  const setWebGPUAvailable = useMolStore((s) => s.setWebGPUAvailable);
  const setIsLoading = useMolStore((s) => s.setIsLoading);
  const setFps = useMolStore((s) => s.setFps);
  const setChainIsolation = useMolStore((s) => s.setChainIsolation);

  useEffect(() => {
    if (cameraRef.current) {
      cameraRef.current.setMode(cameraMode);
    }
  }, [cameraMode]);

  useEffect(() => {
    if (pipelineRef.current) {
      pipelineRef.current.setColorMode(colorMode as ColorMode);
    }
  }, [colorMode]);

  useEffect(() => {
    if (pipelineRef.current) {
      pipelineRef.current.setChainIsolation(
        chainIsolation.isActive,
        chainIsolation.isolatedChainId,
        chainIsolation.fadeOpacity
      );
    }
  }, [chainIsolation]);

  useEffect(() => {
    if (annotationsRef.current) {
      annotationsRef.current.setResidueLabelsVisible(residueLabelsVisible);
      annotationsRef.current.setBackboneRibbonVisible(backboneRibbonVisible);
      annotationsRef.current.setHBondIndicatorsVisible(hBondIndicatorsVisible);
      annotationsRef.current.setPartialChargesVisible(partialChargesVisible);
      annotationsRef.current.setBFactorHeatmapVisible(bFactorHeatmapVisible);
      annotationsRef.current.setLigandHBondNetworkVisible(ligandHBondNetworkVisible);
      annotationsRef.current.setAllOpacities(annotationOpacities);
      forceUpdate(n => n + 1);
    }
  }, [residueLabelsVisible, backboneRibbonVisible, hBondIndicatorsVisible, partialChargesVisible, bFactorHeatmapVisible, ligandHBondNetworkVisible, annotationOpacities]);

  useEffect(() => {
    if (!navigator.gpu) {
      setWebGPUAvailable(false);
      return;
    }

    const canvas = canvasRef.current;
    if (!canvas) return;

    const pipeline = new RenderPipeline();
    const camera = new CameraController([0, 0, 20], [0, 0, 0], [0, 1, 0]);
    const annotations = new AnnotationLayer();

    setIsLoading(true);

    pipeline.init(canvas).then((success) => {
      setIsLoading(false);
      if (!success) {
        setWebGPUAvailable(false);
        return;
      }
      setWebGPUAvailable(true);
      pipelineRef.current = pipeline;
      cameraRef.current = camera;
      annotationsRef.current = annotations;

      const rect = canvas.getBoundingClientRect();
      canvas.width = rect.width * devicePixelRatio;
      canvas.height = rect.height * devicePixelRatio;
      camera.setCanvasSize(canvas.width, canvas.height);

      lastTimeRef.current = performance.now();

      const loop = (time: number) => {
        const dt = (time - lastTimeRef.current) / 1000;
        lastTimeRef.current = time;

        camera.update(dt);
        pipeline.render(camera.getState(), canvas.width, canvas.height);

        frameCountRef.current++;
        fpsAccumRef.current += dt;
        if (fpsAccumRef.current >= 1.0) {
          setFps(Math.round(frameCountRef.current / fpsAccumRef.current));
          frameCountRef.current = 0;
          fpsAccumRef.current = 0;
        }

        forceUpdate(n => n + 1);
        rafRef.current = requestAnimationFrame(loop);
      };

      rafRef.current = requestAnimationFrame(loop);
    });

    return () => {
      cancelAnimationFrame(rafRef.current);
      pipeline.destroy();
    };
  }, []);

  useEffect(() => {
    if (pipelineRef.current && atoms.length > 0) {
      pipelineRef.current.uploadAtoms(atoms, bonds);
      if (annotationsRef.current) {
        annotationsRef.current.update(atoms, bonds);
      }
      if (pipelineRef.current) {
        pipelineRef.current.setColorMode(colorMode as ColorMode);
        pipelineRef.current.setChainIsolation(
          chainIsolation.isActive,
          chainIsolation.isolatedChainId,
          chainIsolation.fadeOpacity
        );
      }
    }
  }, [atoms, bonds]);

  useEffect(() => {
    const handleResize = () => {
      const canvas = canvasRef.current;
      if (!canvas || !cameraRef.current) return;
      const rect = canvas.getBoundingClientRect();
      canvas.width = rect.width * devicePixelRatio;
      canvas.height = rect.height * devicePixelRatio;
      cameraRef.current.setCanvasSize(canvas.width, canvas.height);
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const handleMouseDown = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    cameraRef.current?.handleMouseDown(e.nativeEvent);
  }, []);

  const handleMouseMove = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    cameraRef.current?.handleMouseMove(e.nativeEvent);
  }, []);

  const handleMouseUp = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    cameraRef.current?.handleMouseUp(e.nativeEvent);
  }, []);

  const handleWheel = useCallback((e: React.WheelEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    cameraRef.current?.handleWheel(e.nativeEvent);
  }, []);

  const handleDoubleClick = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!cameraRef.current || atoms.length === 0) return;

    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return;

    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    const cameraState = cameraRef.current.getState();
    const projMatrix = mat4Perspective(degToRad(cameraState.fov), rect.width / rect.height, cameraState.near, cameraState.far);

    let closestAtom: { atom: typeof atoms[0]; dist: number } | null = null;

    for (const atom of atoms) {
      const projected = projectPointToScreen(
        [atom.x, atom.y, atom.z],
        cameraState.viewMatrix,
        projMatrix,
        rect.width,
        rect.height
      );

      if (!projected.visible) continue;

      const dx = projected.x - mouseX;
      const dy = projected.y - mouseY;
      const pixelDist = Math.sqrt(dx * dx + dy * dy);

      if (pixelDist < 20) {
        const worldDist = vec3Distance(
          [cameraState.eye[0], cameraState.eye[1], cameraState.eye[2]],
          [atom.x, atom.y, atom.z]
        );

        if (!closestAtom || worldDist < closestAtom.dist) {
          closestAtom = { atom, dist: worldDist };
        }
      }
    }

    if (closestAtom && closestAtom.atom.chainId) {
      const chainId = closestAtom.atom.chainId;

      if (chainIsolation.isActive && chainIsolation.isolatedChainId === chainId) {
        cameraRef.current.exitChainIsolation(atoms);
        setChainIsolation({ isActive: false, isolatedChainId: null });
      } else {
        cameraRef.current.focusOnChain(atoms, chainId);
        setChainIsolation({ isActive: true, isolatedChainId: chainId });
      }
    }
  }, [atoms, chainIsolation, setChainIsolation]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      cameraRef.current?.handleKeyDown(e);
    };
    const onKeyUp = (e: KeyboardEvent) => {
      cameraRef.current?.handleKeyUp(e);
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
    };
  }, []);

  const renderAnnotationOverlays = () => {
    if (!canvasRef.current || !cameraRef.current || !annotationsRef.current) return null;

    const canvas = canvasRef.current;
    const cameraState = cameraRef.current.getState();
    const rect = canvas.getBoundingClientRect();
    const projMatrix = mat4Perspective(degToRad(cameraState.fov), rect.width / rect.height, cameraState.near, cameraState.far);

    const overlays: React.ReactNode[] = [];

    const partialCharges = annotationsRef.current.getPartialChargesWithOpacity();
    if (partialCharges.opacity > 0 && partialCharges.labels.length > 0) {
      for (const label of partialCharges.labels.slice(0, 50)) {
        const pos = projectPointToScreen(
          [label.position[0], label.position[1], label.position[2]],
          cameraState.viewMatrix,
          projMatrix,
          rect.width,
          rect.height
        );
        if (!pos.visible) continue;

        const chargeText = label.charge >= 0 ? `+${label.charge.toFixed(2)}` : label.charge.toFixed(2);
        const isPositive = label.charge > 0;
        const isNegative = label.charge < 0;

        overlays.push(
          <div
            key={`pc-${label.atomIndex}`}
            className="absolute text-xs font-bold pointer-events-none"
            style={{
              left: pos.x,
              top: pos.y - 14,
              transform: 'translate(-50%, -50%)',
              opacity: partialCharges.opacity,
              color: isPositive ? '#f0a500' : isNegative ? '#ff6b6b' : '#a0a0a0',
              textShadow: '0 0 3px rgba(0,0,0,0.8)',
              fontFamily: '"JetBrains Mono", monospace',
            }}
          >
            {chargeText}
          </div>
        );
      }
    }

    const residueLabels = annotationsRef.current.getResidueLabelsWithOpacity();
    if (residueLabels.opacity > 0 && residueLabels.labels.length > 0) {
      for (const label of residueLabels.labels.slice(0, 100)) {
        const pos = projectPointToScreen(
          [label.position[0], label.position[1], label.position[2]],
          cameraState.viewMatrix,
          projMatrix,
          rect.width,
          rect.height
        );
        if (!pos.visible) continue;

        overlays.push(
          <div
            key={`rl-${label.atomIndex}`}
            className="absolute text-xs pointer-events-none"
            style={{
              left: pos.x,
              top: pos.y - 18,
              transform: 'translate(-50%, -50%)',
              opacity: residueLabels.opacity,
              color: '#ffffff',
              textShadow: '0 0 3px rgba(0,0,0,0.8)',
              fontFamily: '"JetBrains Mono", monospace',
            }}
          >
            {label.text}
          </div>
        );
      }
    }

    const bFactorSpheres = annotationsRef.current.getBFactorSpheresWithOpacity();
    if (bFactorSpheres.opacity > 0 && bFactorHeatmapVisible && bFactorSpheres.spheres.length > 0) {
      const uniqueChains = new Set<string>();
      for (const sphere of bFactorSpheres.spheres) {
        if (sphere.chainId) uniqueChains.add(sphere.chainId);
      }
      if (uniqueChains.size > 0) {
        overlays.push(
          <div
            key="bfactor-legend"
            className="absolute bottom-16 right-4 bg-[rgba(20,20,40,0.85)] backdrop-blur-md border border-white/10 rounded-lg p-3 text-white/90"
            style={{ opacity: bFactorSpheres.opacity * 0.95 }}
          >
            <div className="text-xs font-semibold mb-2 uppercase tracking-wide">B-Factor (Å²)</div>
            <div className="flex items-center gap-2">
              <div className="w-24 h-3 rounded" style={{
                background: 'linear-gradient(to right, #0000ff, #00ffff, #00ff00, #ffff00, #ff0000)'
              }} />
              <div className="flex justify-between w-full text-[10px] text-white/60 font-mono">
                <span>0</span>
                <span>50</span>
                <span>100+</span>
              </div>
            </div>
          </div>
        );
      }
    }

    return overlays;
  };

  if (!isWebGPUAvailable) {
    return (
      <div className="flex h-full w-full items-center justify-center bg-[#1a1a2e] text-white">
        <div className="text-center">
          <p className="text-2xl font-bold">WebGPU Not Supported</p>
          <p className="mt-2 text-sm text-gray-400">
            Please use a WebGPU-compatible browser.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="relative h-full w-full bg-[#1a1a2e]">
      <canvas
        ref={canvasRef}
        className="h-full w-full block"
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUp}
        onMouseLeave={handleMouseUp}
        onWheel={handleWheel}
        onDoubleClick={handleDoubleClick}
      />

      {renderAnnotationOverlays()}

      {chainIsolation.isActive && (
        <div className="absolute top-4 left-1/2 -translate-x-1/2 bg-[rgba(240,165,0,0.9)] text-white px-4 py-2 rounded-lg text-sm flex items-center gap-3 backdrop-blur-sm">
          <span>Chain Isolation: <strong>{chainIsolation.isolatedChainId}</strong></span>
          <button
            className="px-2 py-1 bg-white/20 hover:bg-white/30 rounded text-xs transition-colors"
            onClick={() => {
              if (cameraRef.current && atoms.length > 0) {
                cameraRef.current.exitChainIsolation(atoms);
                setChainIsolation({ isActive: false, isolatedChainId: null });
              }
            }}
          >
            Exit (double-click)
          </button>
        </div>
      )}

      {isLoading && (
        <div className="absolute inset-0 flex items-center justify-center bg-[#1a1a2e]/80">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/30 border-t-white" />
        </div>
      )}

      <div
        className="absolute bottom-3 right-3 rounded bg-black/50 px-2 py-1 text-xs text-white/70"
        style={{ fontFamily: '"JetBrains Mono", monospace' }}
      >
        {atomCount} atoms | {bondCount} bonds | {fps} FPS
        {colorMode !== 'element' && ` | ${colorMode}`}
      </div>
    </div>
  );
}

