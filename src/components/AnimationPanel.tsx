import { Play, Pause } from 'lucide-react';
import { useMolStore } from '@/store/useMolStore';

const barClass =
  'bg-[rgba(20,20,40,0.85)] backdrop-blur-md border border-white/10 rounded-lg text-white/90 font-["DM_Sans"]';

export default function AnimationPanel() {
  const {
    isPlaying,
    animationTime,
    animationEasing,
    animationSpeed,
    setIsPlaying,
    setAnimationTime,
    setAnimationEasing,
    setAnimationSpeed,
  } = useMolStore();

  return (
    <div
      className={`${barClass} fixed bottom-4 left-1/2 -translate-x-1/2 z-20 flex items-center gap-4 px-5 py-3`}
    >
      <button
        onClick={() => setIsPlaying(!isPlaying)}
        className="p-1.5 rounded-md bg-white/5 hover:bg-white/10 text-white/80 hover:text-white transition-colors"
      >
        {isPlaying ? <Pause size={16} /> : <Play size={16} />}
      </button>

      <div className="flex items-center gap-2 min-w-0">
        <input
          type="range"
          min={0}
          max={100}
          value={Math.round(animationTime * 100)}
          onChange={(e) => setAnimationTime(Number(e.target.value) / 100)}
          className="w-32 h-1 appearance-none bg-white/20 rounded-full accent-[#00d4aa] cursor-pointer"
        />
        <span className="text-xs text-white/50 w-14 text-right tabular-nums">
          {(animationTime * 100).toFixed(0)}%
        </span>
      </div>

      <div className="flex items-center gap-1.5">
        <span className="text-xs text-white/50">Ease</span>
        <select
          value={animationEasing}
          onChange={(e) =>
            setAnimationEasing(e.target.value as 'linear' | 'smoothstep')
          }
          className="bg-white/5 border border-white/10 rounded px-1.5 py-0.5 text-xs text-white/80 focus:outline-none focus:border-[#00d4aa] cursor-pointer"
        >
          <option value="linear" className="bg-[#141428]">Linear</option>
          <option value="smoothstep" className="bg-[#141428]">Smooth Step</option>
        </select>
      </div>

      <div className="flex items-center gap-1.5">
        <span className="text-xs text-white/50">Speed</span>
        <input
          type="range"
          min={1}
          max={50}
          value={Math.round(animationSpeed * 10)}
          onChange={(e) => setAnimationSpeed(Number(e.target.value) / 10)}
          className="w-16 h-1 appearance-none bg-white/20 rounded-full accent-[#00d4aa] cursor-pointer"
        />
        <span className="text-xs text-white/50 w-8 tabular-nums">
          {animationSpeed.toFixed(1)}x
        </span>
      </div>

      <div className="text-xs text-white/40 tabular-nums border-l border-white/10 pl-4">
        t = {animationTime.toFixed(3)}
      </div>
    </div>
  );
}
