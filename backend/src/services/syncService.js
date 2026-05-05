const { v4: uuidv4 } = require('uuid');
const OperationLog = require('../models/OperationLog');
const SceneObject = require('../models/SceneObject');
const Scene = require('../models/Scene');

const sceneSessions = new Map();
const userScenes = new Map();

function generateOperationId() {
  return `op_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

function generateObjectId(objectType) {
  const prefix = {
    wall: 'obj_wall',
    door: 'obj_door',
    window: 'obj_window',
    furniture: 'obj_furniture'
  }[objectType] || 'obj_generic';
  
  return `${prefix}_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;
}

function getSceneSession(sceneId) {
  if (!sceneSessions.has(sceneId)) {
    sceneSessions.set(sceneId, {
      sceneId,
      users: new Map(),
      version: 0,
      operationQueue: []
    });
  }
  return sceneSessions.get(sceneId);
}

async function initializeSceneVersion(sceneId) {
  let scene = await Scene.findOne({ scene_id: sceneId });
  
  if (!scene) {
    scene = new Scene({
      scene_id: sceneId,
      name: `Scene_${sceneId.substr(0, 8)}`,
      creator_id: 'system',
      current_version: 0
    });
    await scene.save();
  }
  
  const session = getSceneSession(sceneId);
  session.version = scene.current_version;
  
  return scene;
}

async function handleUserJoin(io, socket, data) {
  const { scene_id, user_id, user_name } = data;
  
  if (!scene_id || !user_id) {
    socket.emit('error', { message: 'Missing scene_id or user_id' });
    return;
  }
  
  await initializeSceneVersion(scene_id);
  
  const session = getSceneSession(scene_id);
  const userInfo = {
    socketId: socket.id,
    userId: user_id,
    userName: user_name || `User_${user_id.substr(0, 6)}`,
    joinTime: new Date()
  };
  
  session.users.set(socket.id, userInfo);
  userScenes.set(socket.id, scene_id);
  
  socket.join(scene_id);
  
  const existingObjects = await SceneObject.find({
    scene_id,
    is_deleted: false
  });
  
  const usersList = Array.from(session.users.values()).map(u => ({
    user_id: u.userId,
    user_name: u.userName
  }));
  
  socket.emit('join_success', {
    scene_id,
    current_version: session.version,
    objects: existingObjects.map(obj => obj.toObject()),
    users: usersList
  });
  
  io.to(scene_id).emit('user_joined', {
    user_id: user_id,
    user_name: userInfo.userName,
    users: usersList
  });
  
  console.log(`User ${user_id} joined scene ${scene_id}`);
}

async function handleSceneOperation(io, socket, data) {
  const { operation_type, object_type, parameters, target_object } = data;
  const sceneId = userScenes.get(socket.id);
  
  if (!sceneId) {
    socket.emit('error', { message: 'Not joined to any scene' });
    return;
  }
  
  const session = getSceneSession(sceneId);
  const userInfo = session.users.get(socket.id);
  
  if (!userInfo) {
    socket.emit('error', { message: 'User not found in session' });
    return;
  }
  
  const operationId = generateOperationId();
  const newVersion = ++session.version;
  
  let resultObjectId = target_object;
  let operationData = {
    operation_id: operationId,
    scene_id: sceneId,
    operation_type,
    target_object,
    parameters,
    user_id: userInfo.userId,
    version: newVersion,
    timestamp: new Date()
  };
  
  try {
    switch (operation_type) {
      case 'object_create':
        resultObjectId = generateObjectId(object_type);
        operationData.target_object = resultObjectId;
        operationData.object_type = object_type;
        
        const newObject = new SceneObject({
          object_id: resultObjectId,
          scene_id: sceneId,
          object_type,
          transform: parameters.transform || {
            position: { x: 0, y: 0, z: 0 },
            rotation: { x: 0, y: 0, z: 0 },
            scale: { x: 1, y: 1, z: 1 }
          },
          material_id: parameters.material_id || 'mat_default_01',
          asset_id: parameters.asset_id,
          creator_id: userInfo.userId,
          version: newVersion
        });
        
        await newObject.save();
        break;
        
      case 'transform_update':
        const updateData = {
          $set: {
            version: newVersion
          }
        };
        
        if (parameters.transform) {
          if (parameters.transform.position) {
            updateData.$set['transform.position'] = parameters.transform.position;
          }
          if (parameters.transform.rotation) {
            updateData.$set['transform.rotation'] = parameters.transform.rotation;
          }
          if (parameters.transform.scale) {
            updateData.$set['transform.scale'] = parameters.transform.scale;
          }
        }
        
        await SceneObject.findOneAndUpdate(
          { object_id: target_object, scene_id: sceneId },
          updateData
        );
        break;
        
      case 'object_delete':
        await SceneObject.findOneAndUpdate(
          { object_id: target_object, scene_id: sceneId },
          {
            $set: {
              is_deleted: true,
              version: newVersion
            }
          }
        );
        break;
        
      case 'material_update':
        await SceneObject.findOneAndUpdate(
          { object_id: target_object, scene_id: sceneId },
          {
            $set: {
              material_id: parameters.material_id,
              version: newVersion
            }
          }
        );
        break;
        
      default:
        console.warn(`Unknown operation type: ${operation_type}`);
    }
    
    const operationLog = new OperationLog(operationData);
    await operationLog.save();
    
    await Scene.findOneAndUpdate(
      { scene_id: sceneId },
      { current_version: newVersion }
    );
    
    const broadcastData = {
      operation_id: operationId,
      version: newVersion,
      object_id: resultObjectId,
      operation_type,
      object_type,
      parameters,
      user_id: userInfo.userId,
      timestamp: operationData.timestamp
    };
    
    io.to(sceneId).emit('operation_broadcast', broadcastData);
    
    socket.emit('operation_ack', {
      operation_id: operationId,
      version: newVersion,
      success: true
    });
    
  } catch (error) {
    console.error('Operation failed:', error);
    session.version--;
    
    socket.emit('operation_ack', {
      operation_id: operationId,
      success: false,
      error: error.message
    });
  }
}

function handleUserLeave(io, socket) {
  const sceneId = userScenes.get(socket.id);
  
  if (!sceneId) return;
  
  const session = getSceneSession(sceneId);
  const userInfo = session.users.get(socket.id);
  
  if (userInfo) {
    session.users.delete(socket.id);
    userScenes.delete(socket.id);
    
    const usersList = Array.from(session.users.values()).map(u => ({
      user_id: u.userId,
      user_name: u.userName
    }));
    
    io.to(sceneId).emit('user_left', {
      user_id: userInfo.userId,
      users: usersList
    });
    
    socket.leave(sceneId);
    
    console.log(`User ${userInfo.userId} left scene ${sceneId}`);
  }
}

function initialize(io) {
  io.on('connection', (socket) => {
    console.log(`New socket connection: ${socket.id}`);
    
    socket.on('join_scene', (data) => handleUserJoin(io, socket, data));
    
    socket.on('scene_operation', (data) => handleSceneOperation(io, socket, data));
    
    socket.on('leave_scene', () => handleUserLeave(io, socket));
    
    socket.on('disconnect', () => {
      handleUserLeave(io, socket);
      console.log(`Socket disconnected: ${socket.id}`);
    });
  });
}

module.exports = {
  initialize,
  handleUserJoin,
  handleSceneOperation,
  handleUserLeave
};
