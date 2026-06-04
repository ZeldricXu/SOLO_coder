import Viewport from '@/components/Viewport';
import FilePanel from '@/components/FilePanel';
import CameraToolbar from '@/components/CameraToolbar';
import MeasurementPanel from '@/components/MeasurementPanel';
import AnnotationPanel from '@/components/AnnotationPanel';
import AnimationPanel from '@/components/AnimationPanel';

export default function App() {
  return (
    <div className="relative w-full h-screen overflow-hidden bg-[#1a1a2e]">
      <Viewport />

      <FilePanel />
      <CameraToolbar />
      <MeasurementPanel />
      <AnnotationPanel />
      <AnimationPanel />
    </div>
  );
}
