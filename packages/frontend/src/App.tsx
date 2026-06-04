import React, { useEffect } from 'react';
import { DndProvider } from 'react-dnd';
import { HTML5Backend } from 'react-dnd-html5-backend';
import SceneEditor from './components/SceneEditor';
import Viewport3D from './components/Viewport3D';
import DataAnalyzer from './components/DataAnalyzer';
import { useSimulationStore } from './store/simulationStore';

const App: React.FC = () => {
  const { initEngine } = useSimulationStore();

  useEffect(() => {
    initEngine();
  }, [initEngine]);

  return (
    <DndProvider backend={HTML5Backend}>
      <div style={{
        width: '100vw',
        height: '100vh',
        display: 'flex',
        flexDirection: 'row',
        overflow: 'hidden',
        background: '#1a1a1a',
      }}>
        <SceneEditor />
        <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
          <Viewport3D />
        </div>
        <DataAnalyzer />
      </div>
    </DndProvider>
  );
};

export default App;
