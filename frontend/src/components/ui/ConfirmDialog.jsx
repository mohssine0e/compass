import Button from './Button'
import Modal from './Modal'

/**
 * ConfirmDialog — the one way to confirm a destructive/consequential action.
 *
 * Replaces `window.confirm(...)` (V4-4.4, 2026-07-30 user audit: three call sites in
 * RoadmapDetail used the native browser dialog, which can't be styled, doesn't get the shared
 * focus-trap/Escape handling every other dialog in the app gets, and looks like an OS alert
 * rather than the app's own voice). Same focus/Escape/scroll-lock behavior as every other modal,
 * for free, via the shared `Modal` component.
 *
 * @param {string} title
 * @param {string} message
 * @param {string} [confirmLabel='Delete']
 * @param {boolean} [danger=true]  red "danger" button vs. brass "primary" for a non-destructive confirm
 * @param {() => void} onConfirm
 * @param {() => void} onCancel
 * @example
 *   <ConfirmDialog
 *     title="Delete this step?"
 *     message={`Delete "${node.text}"? This can't be undone.`}
 *     onConfirm={reallyDelete}
 *     onCancel={close}
 *   />
 */
export default function ConfirmDialog({ title, message, confirmLabel = 'Delete', danger = true, onConfirm, onCancel }) {
  return (
    <Modal onClose={onCancel} size="md" title={title}>
      <p className="ui-confirm-message">{message}</p>
      <div className="ui-confirm-actions">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </div>
    </Modal>
  )
}
