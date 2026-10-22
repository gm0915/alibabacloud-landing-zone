import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { DispatchGuidanceInput } from './DispatchGuidanceInput';
import { getDispatchGuidance, sendDispatchGuidance } from '../api';
vi.mock('../api', () => ({ getDispatchGuidance: vi.fn(), sendDispatchGuidance: vi.fn() }));
vi.mock('@/shared/auth/useAccessCommand', () => ({ useAccessCommand: () => (_: string, __: string, action: () => void) => action() }));
beforeEach(() => { vi.clearAllMocks(); vi.mocked(getDispatchGuidance).mockResolvedValue([]); });
it('preserves instructions and retries with the same request key after a lost response', async () => {
  vi.mocked(sendDispatchGuidance).mockRejectedValueOnce(new Error('lost response')).mockResolvedValueOnce([
    { id: 1, dispatchId: 42, workitemId: 7, actorId: 3, content: 'redirect <script>', status: 'QUEUED', createdAt: '' },
  ]);
  render(<DispatchGuidanceInput dispatchId={42} />);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'redirect <script>' } });
  fireEvent.click(screen.getByRole('button'));
  await screen.findByText('lost response');
  fireEvent.click(screen.getByRole('button'));
  await waitFor(() => expect(sendDispatchGuidance).toHaveBeenCalledTimes(2));
  const calls = vi.mocked(sendDispatchGuidance).mock.calls;
  expect(calls[0]).toEqual([42, 'redirect <script>', expect.any(String)]);
  expect(calls[1]).toEqual(calls[0]);
  await screen.findByText('redirect <script>');
  expect(screen.getByRole('textbox')).toHaveValue('');
});
it('sends when randomUUID is missing in an HTTP context', async () => {
  const webCrypto = globalThis.crypto;
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: {} });
  vi.mocked(sendDispatchGuidance).mockResolvedValue([]);
  try {
    render(<DispatchGuidanceInput dispatchId={42} />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'steer left' } });
    fireEvent.click(screen.getByRole('button'));
    await waitFor(() => expect(sendDispatchGuidance).toHaveBeenCalledWith(
      42, 'steer left', expect.stringMatching(/^[a-zA-Z0-9-]{1,64}$/),
    ));
  } finally {
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: webCrypto });
  }
});
it('does not submit blank guidance', () => {
  render(<DispatchGuidanceInput dispatchId={42} />);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '   ' } });
  expect(screen.getByRole('button')).toBeDisabled();
  expect(sendDispatchGuidance).not.toHaveBeenCalled();
});
