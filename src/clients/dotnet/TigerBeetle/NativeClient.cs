using System;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using static TigerBeetle.AssertionException;
using static TigerBeetle.Native;

namespace TigerBeetle;

internal sealed class NativeClient : IDisposable
{
    private readonly GCHandle clientHandle;

    private unsafe delegate InitializationStatus InitFunction(
                TBClient* out_client,
                UInt128Extensions.UnsafeU128* cluster_id,
                byte* address_ptr,
                uint address_len,
                IntPtr completion_ctx,
                delegate* unmanaged[Cdecl]<IntPtr, TBPacket*, ulong, byte*, uint, void> completion_callback
            );

    private NativeClient(GCHandle clientHandle)
    {
        this.clientHandle = clientHandle;
    }

    ~NativeClient()
    {
        Dispose();
        // It must be released during GC.
        clientHandle.Free();
    }

    private static byte[] GetBytes(string[] addresses)
    {
        if (addresses == null) throw new ArgumentNullException(nameof(addresses));
        return Encoding.UTF8.GetBytes(string.Join(',', addresses) + "\0");
    }

    public static NativeClient Init(UInt128 clusterID, string[] addresses)
    {
        unsafe
        {
            return CallInit(tb_client_init, clusterID, addresses);
        }
    }

    public static NativeClient InitEcho(UInt128 clusterID, string[] addresses)
    {
        unsafe
        {
            return CallInit(tb_client_init_echo, clusterID, addresses);
        }
    }

    private static NativeClient CallInit(InitFunction initFunction, UInt128Extensions.UnsafeU128 clusterID, string[] addresses)
    {
        var addressesBytes = GetBytes(addresses);
        unsafe
        {
            var clientHandle = GCHandle.Alloc(new TBClient(), GCHandleType.Pinned);
            fixed (byte* addressPtr = addressesBytes)
            {
                var status = initFunction(
                    (TBClient*)clientHandle.AddrOfPinnedObject(),
                    &clusterID,
                    addressPtr,
                    (uint)addressesBytes.Length - 1,
                    IntPtr.Zero,
                    &OnCompletionCallback
                );

                if (status != InitializationStatus.Success)
                {
                    clientHandle.Free();
                    throw new InitializationException(status);
                }

                return new NativeClient(clientHandle);
            }
        }
    }

    public TResult[] CallRequest<TResult, TBody>(TBOperation operation, ReadOnlySpan<TBody> batch)
        where TResult : unmanaged
        where TBody : unmanaged
    {
        unsafe
        {
            fixed (void* pointer = batch)
            {
                var blockingRequest = new BlockingRequest<TResult, TBody>(operation);
                blockingRequest.Submit(this, pointer, batch.Length);
                return blockingRequest.Wait();
            }
        }
    }

    public async Task<TResult[]> CallRequestAsync<TResult, TBody>(TBOperation operation, ReadOnlyMemory<TBody> batch)
        where TResult : unmanaged
        where TBody : unmanaged
    {
        using (var memoryHandler = batch.Pin())
        {
            var asyncRequest = new AsyncRequest<TResult, TBody>(operation);

            unsafe
            {
                asyncRequest.Submit(this, memoryHandler.Pointer, batch.Length);
            }

            return await asyncRequest.Wait().ConfigureAwait(continueOnCapturedContext: false);
        }
    }

    public unsafe void Submit(TBPacket* packet)
    {
        unsafe
        {
            var status = tb_client_submit((TBClient*)clientHandle.AddrOfPinnedObject(), packet);
            ObjectDisposedException.ThrowIf(status == ClientStatus.Invalid, this);
        }
    }

    public void Dispose()
    {
        // Do not call `GC.SuppressFinalize` during `Dispose`.
        // The `client_handle` will be freed by the destructor.
        unsafe
        {
            _ = tb_client_deinit((TBClient*)clientHandle.AddrOfPinnedObject());
        }
    }

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    private unsafe static void OnCompletionCallback(IntPtr ctx, TBPacket* packet, ulong timestamp, byte* result, uint resultLen)
    {
        _ = timestamp;

        try
        {
            AssertTrue(ctx == IntPtr.Zero);
            OnComplete(packet, result, resultLen);
        }
        catch (Exception e)
        {
            // The caller is unmanaged code, so if an exception occurs here we should force panic.
            Environment.FailFast("Failed to process a packet in the OnCompletionCallback", e);
        }
    }

    private unsafe static void OnComplete(TBPacket* packet, byte* result, uint resultLen)
    {
        var span = resultLen > 0 ? new ReadOnlySpan<byte>(result, (int)resultLen) : ReadOnlySpan<byte>.Empty;
        NativeRequest.OnComplete(packet, span);
    }
}
