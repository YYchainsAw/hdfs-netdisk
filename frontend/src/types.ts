export interface DiskNode {
  id: string
  parentId: string
  name: string
  type: 'FOLDER' | 'FILE'
  size: number | null
  modified: string
  data?: File | Blob
}

export interface Breadcrumb {
  id: string
  name: string
}

export type SaveFileResult =
  | { status: 'added'; name: string }
  | { status: 'conflict'; name: string }
  | { status: 'skipped'; name: string }
  | { status: 'error'; name: string; message: string }
