import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";

export interface Chapter {
  id: string;
  title: string;
  content: string;
  order: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface Volume {
  id: string;
  title: string;
  description: string;
  order: number;
  chapters: Chapter[];
  createdAt: Date;
  updatedAt: Date;
}

export interface Novel {
  id: string;
  title: string;
  description: string;
  volumes: Volume[];
  createdAt: Date;
  updatedAt: Date;
}

interface NovelState {
  currentNovel: Novel | null;
  currentVolumeId: string | null;
  currentChapterId: string | null;
  isLoading: boolean;
  error: string | null;
  
  createNovel: (title: string, description?: string) => void;
  createVolume: (title: string, description?: string) => void;
  createChapter: (volumeId: string, title: string) => void;
  selectChapter: (volumeId: string, chapterId: string) => void;
  updateChapterContent: (chapterId: string, content: string) => void;
  updateChapterTitle: (chapterId: string, title: string) => void;
  updateVolumeTitle: (volumeId: string, title: string) => void;
  deleteChapter: (volumeId: string, chapterId: string) => void;
  deleteVolume: (volumeId: string) => void;
}

const createSampleNovel = (): Novel => {
  const novelId = uuidv4();
  const volumeId = uuidv4();
  const chapterId = uuidv4();

  return {
    id: novelId,
    title: "我的第一本小说",
    description: "这是一本关于冒险与成长的小说",
    createdAt: new Date(),
    updatedAt: new Date(),
    volumes: [
      {
        id: volumeId,
        title: "第一卷：启程",
        description: "主角的冒险开始了",
        order: 1,
        createdAt: new Date(),
        updatedAt: new Date(),
        chapters: [
          {
            id: chapterId,
            title: "第一章：初遇",
            content: "",
            order: 1,
            createdAt: new Date(),
            updatedAt: new Date(),
          },
        ],
      },
    ],
  };
};

export const useNovelStore = create<NovelState>((set) => ({
  currentNovel: createSampleNovel(),
  currentVolumeId: createSampleNovel().volumes[0]?.id || null,
  currentChapterId: createSampleNovel().volumes[0]?.chapters[0]?.id || null,
  isLoading: false,
  error: null,

  createNovel: (title, description = "") => {
    const novel: Novel = {
      id: uuidv4(),
      title,
      description,
      createdAt: new Date(),
      updatedAt: new Date(),
      volumes: [],
    };

    set({
      currentNovel: novel,
      currentVolumeId: null,
      currentChapterId: null,
    });
  },

  createVolume: (title, description = "") => {
    set((state) => {
      if (!state.currentNovel) return state;

      const newVolume: Volume = {
        id: uuidv4(),
        title,
        description,
        order: state.currentNovel.volumes.length + 1,
        chapters: [],
        createdAt: new Date(),
        updatedAt: new Date(),
      };

      return {
        currentNovel: {
          ...state.currentNovel,
          volumes: [...state.currentNovel.volumes, newVolume],
          updatedAt: new Date(),
        },
        currentVolumeId: newVolume.id,
      };
    });
  },

  createChapter: (volumeId, title) => {
    set((state) => {
      if (!state.currentNovel) return state;

      const volume = state.currentNovel.volumes.find((v) => v.id === volumeId);
      if (!volume) return state;

      const newChapter: Chapter = {
        id: uuidv4(),
        title,
        content: "",
        order: volume.chapters.length + 1,
        createdAt: new Date(),
        updatedAt: new Date(),
      };

      const updatedNovel = {
        ...state.currentNovel,
        volumes: state.currentNovel.volumes.map((v) => {
          if (v.id === volumeId) {
            return {
              ...v,
              chapters: [...v.chapters, newChapter],
              updatedAt: new Date(),
            };
          }
          return v;
        }),
        updatedAt: new Date(),
      };

      return {
        currentNovel: updatedNovel,
        currentChapterId: newChapter.id,
        currentVolumeId: volumeId,
      };
    });
  },

  selectChapter: (volumeId, chapterId) => {
    set({
      currentVolumeId: volumeId,
      currentChapterId: chapterId,
    });
  },

  updateChapterContent: (chapterId, content) => {
    set((state) => {
      if (!state.currentNovel) return state;

      const updatedNovel = {
        ...state.currentNovel,
        volumes: state.currentNovel.volumes.map((volume) => ({
          ...volume,
          chapters: volume.chapters.map((chapter) => {
            if (chapter.id === chapterId) {
              return {
                ...chapter,
                content,
                updatedAt: new Date(),
              };
            }
            return chapter;
          }),
        })),
        updatedAt: new Date(),
      };

      return { currentNovel: updatedNovel };
    });
  },

  updateChapterTitle: (chapterId, title) => {
    set((state) => {
      if (!state.currentNovel) return state;

      const updatedNovel = {
        ...state.currentNovel,
        volumes: state.currentNovel.volumes.map((volume) => ({
          ...volume,
          chapters: volume.chapters.map((chapter) => {
            if (chapter.id === chapterId) {
              return {
                ...chapter,
                title,
                updatedAt: new Date(),
              };
            }
            return chapter;
          }),
        })),
        updatedAt: new Date(),
      };

      return { currentNovel: updatedNovel };
    });
  },

  updateVolumeTitle: (volumeId, title) => {
    set((state) => {
      if (!state.currentNovel) return state;

      const updatedNovel = {
        ...state.currentNovel,
        volumes: state.currentNovel.volumes.map((volume) => {
          if (volume.id === volumeId) {
            return {
              ...volume,
              title,
              updatedAt: new Date(),
            };
          }
          return volume;
        }),
        updatedAt: new Date(),
      };

      return { currentNovel: updatedNovel };
    });
  },

  deleteChapter: (volumeId, chapterId) => {
    set((state) => {
      if (!state.currentNovel) return state;

      const updatedNovel = {
        ...state.currentNovel,
        volumes: state.currentNovel.volumes.map((volume) => {
          if (volume.id === volumeId) {
            return {
              ...volume,
              chapters: volume.chapters.filter((chapter) => chapter.id !== chapterId),
              updatedAt: new Date(),
            };
          }
          return volume;
        }),
        updatedAt: new Date(),
      };

      const newCurrentChapterId =
        state.currentChapterId === chapterId ? null : state.currentChapterId;

      return {
        currentNovel: updatedNovel,
        currentChapterId: newCurrentChapterId,
      };
    });
  },

  deleteVolume: (volumeId) => {
    set((state) => {
      if (!state.currentNovel) return state;

      const updatedNovel = {
        ...state.currentNovel,
        volumes: state.currentNovel.volumes.filter((volume) => volume.id !== volumeId),
        updatedAt: new Date(),
      };

      const newCurrentVolumeId =
        state.currentVolumeId === volumeId ? null : state.currentVolumeId;

      return {
        currentNovel: updatedNovel,
        currentVolumeId: newCurrentVolumeId,
        currentChapterId: newCurrentVolumeId ? state.currentChapterId : null,
      };
    });
  },
}));
