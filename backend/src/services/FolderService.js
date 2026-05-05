const Folder = require('../models/Folder');
const Document = require('../models/Document');
const { v4: uuidv4 } = require('uuid');
const _ = require('lodash');

class FolderService {
  async createFolder(name, parent_id = null, created_by) {
    const folder = new Folder({
      folder_id: uuidv4(),
      name,
      parent_id,
      created_by,
      order: 0
    });
    
    await folder.save();
    
    if (parent_id) {
      await this.reorderSiblings(parent_id);
    }
    
    return folder.toObject();
  }

  async getFolder(folder_id) {
    const folder = await Folder.findOne({ folder_id });
    
    if (!folder) {
      throw new Error(`Folder not found: ${folder_id}`);
    }
    
    return folder.toObject();
  }

  async getFolderTree(parent_id = null) {
    const folders = await Folder.find({ parent_id })
      .sort({ order: 1 })
      .exec();
    
    const documents = await Document.find({ folder_id: parent_id })
      .sort({ last_edited_at: -1 })
      .exec();
    
    const children = [];
    
    for (const folder of folders) {
      const childTree = await this.getFolderTree(folder.folder_id);
      children.push({
        type: 'folder',
        ...folder.toObject(),
        children: childTree
      });
    }
    
    for (const doc of documents) {
      children.push({
        type: 'document',
        ...doc.toObject()
      });
    }
    
    return children;
  }

  async getFullTree() {
    return this.getFolderTree(null);
  }

  async updateFolder(folder_id, updates) {
    const folder = await Folder.findOne({ folder_id });
    
    if (!folder) {
      throw new Error(`Folder not found: ${folder_id}`);
    }
    
    const allowedUpdates = ['name', 'is_expanded', 'order'];
    const filteredUpdates = _.pick(updates, allowedUpdates);
    
    Object.assign(folder, filteredUpdates);
    await folder.save();
    
    return folder.toObject();
  }

  async moveFolder(folder_id, new_parent_id = null) {
    const folder = await Folder.findOne({ folder_id });
    
    if (!folder) {
      throw new Error(`Folder not found: ${folder_id}`);
    }
    
    if (folder.parent_id === new_parent_id) {
      return folder.toObject();
    }
    
    const oldParentId = folder.parent_id;
    
    folder.parent_id = new_parent_id;
    await folder.save();
    
    if (oldParentId) {
      await this.reorderSiblings(oldParentId);
    }
    if (new_parent_id) {
      await this.reorderSiblings(new_parent_id);
    }
    
    return folder.toObject();
  }

  async deleteFolder(folder_id, recursive = false) {
    const folder = await Folder.findOne({ folder_id });
    
    if (!folder) {
      throw new Error(`Folder not found: ${folder_id}`);
    }
    
    if (!recursive) {
      const childFolders = await Folder.countDocuments({ parent_id: folder_id });
      const childDocs = await Document.countDocuments({ folder_id: folder_id });
      
      if (childFolders > 0 || childDocs > 0) {
        throw new Error('Folder is not empty. Use recursive=true to delete all contents.');
      }
    }
    
    if (recursive) {
      const childFolders = await Folder.find({ parent_id: folder_id });
      for (const child of childFolders) {
        await this.deleteFolder(child.folder_id, true);
      }
      
      await Document.deleteMany({ folder_id: folder_id });
    }
    
    const parentId = folder.parent_id;
    await Folder.deleteOne({ folder_id });
    
    if (parentId) {
      await this.reorderSiblings(parentId);
    }
    
    return { deleted: true, folder_id };
  }

  async reorderSiblings(parent_id) {
    const siblings = await Folder.find({ parent_id })
      .sort({ order: 1, created_at: 1 })
      .exec();
    
    for (let i = 0; i < siblings.length; i++) {
      siblings[i].order = i;
      await siblings[i].save();
    }
    
    return siblings.map(s => s.toObject());
  }

  async reorderItems(parent_id, ordered_ids) {
    const folders = await Folder.find({ parent_id });
    const folderMap = new Map(folders.map(f => [f.folder_id, f]));
    
    for (let i = 0; i < ordered_ids.length; i++) {
      const itemId = ordered_ids[i];
      const folder = folderMap.get(itemId);
      if (folder) {
        folder.order = i;
        await folder.save();
      }
    }
    
    return this.getFolderTree(parent_id);
  }

  async getFolderPath(folder_id) {
    const path = [];
    let currentId = folder_id;
    
    while (currentId) {
      const folder = await Folder.findOne({ folder_id: currentId });
      if (!folder) break;
      
      path.unshift({
        folder_id: folder.folder_id,
        name: folder.name
      });
      
      currentId = folder.parent_id;
    }
    
    return path;
  }

  async searchFolders(name, parent_id = null) {
    const query = { name: { $regex: name, $options: 'i' } };
    if (parent_id !== null) {
      query.parent_id = parent_id;
    }
    
    const folders = await Folder.find(query)
      .sort({ name: 1 })
      .exec();
    
    return folders.map(f => f.toObject());
  }

  async expandCollapse(folder_id, is_expanded) {
    const folder = await Folder.findOne({ folder_id });
    
    if (!folder) {
      throw new Error(`Folder not found: ${folder_id}`);
    }
    
    folder.is_expanded = is_expanded;
    await folder.save();
    
    return folder.toObject();
  }
}

module.exports = new FolderService();
