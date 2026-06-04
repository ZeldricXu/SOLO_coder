import { RotateCw, Globe, Navigation, Home } from 'lucide-react';
import { useMolStore } from '@/store/useMolStore';

type CameraMode = 'orbit' | 'trackball' | 'fly';

const modes: { mode: CameraMode; icon: typeof RotateCw; label: string }[] = [
  { mode: 'orbit', icon: RotateCw, label: 'Orbit' },
  { mode: 'trackball', icon: Globe, label: 'Trackball' },
  { mode: 'fly', icon: Navigation, label: 'Fly' },
];

export default function CameraToolbar() {
  const { cameraMode, setCameraMode } = useMolStore();

  const handleReset = () => {
    setCameraMode('orbit');
  };

  return (
    <div className="fixed top-4 right-4 z-20 flex flex-col gap-1 bg-[rgba(20,20,40,0.85)] backdrop-blur-md border border-white/10 rounded-lg p-1.5 font-['DM_Sans']">
      {modes.map(({ mode, icon: Icon, label }) => {
        const active = cameraMode === mode;
        return (
          <button
            key={mode}
            onClick={() => setCameraMode(mode)}
            title={label}
            className={`p-2 rounded-md transition-colors ${
              active
                ? 'bg-[#00d4aa]/20 text-[#00d4aa]'
                : 'text-white/60 hover:bg-white/10 hover:text-white/90'
            }`}
          >
            <Icon size={18} />
          </button>
        );
      })}

      <div className="my-0.5 border-t border-white/10" />

      <button
        onClick={handleReset}
        title="Reset View"
        className="p-2 rounded-md text-white/60 hover:bg-white/10 hover:text-white/90 transition-colors"
      >
        <Home size={18} />
      </button>
    </div>
  );
}
