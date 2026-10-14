#include "audio/wasapi.h"
#include <endpointvolume.h>

namespace aurora::audio {
namespace {

// software endpoint volume is bypassed by exclusive streams, so only a driver-reported hardware control counts
HRESULT hardwareControl(const std::wstring& id, ComPtr<IAudioEndpointVolume>& volume, DWORD& support) {
    ComPtr<IMMDevice> device;
    HRESULT hr = findDevice(id, &device);
    if (SUCCEEDED(hr)) hr = device->Activate(__uuidof(IAudioEndpointVolume), CLSCTX_ALL, nullptr, &volume);
    if (SUCCEEDED(hr)) hr = volume->QueryHardwareSupport(&support);
    if (FAILED(hr)) return hr;
    return (support & ENDPOINT_HARDWARE_SUPPORT_VOLUME) ? S_OK : E_NOTIMPL;
}

}

HRESULT readHardwareVolume(const std::wstring& id, HardwareLevel& level) {
    ComPtr<IAudioEndpointVolume> volume;
    DWORD support = 0;
    HRESULT hr = hardwareControl(id, volume, support);
    if (SUCCEEDED(hr)) hr = volume->GetVolumeRange(&level.minDb, &level.maxDb, &level.stepDb);
    if (SUCCEEDED(hr)) hr = volume->GetMasterVolumeLevel(&level.db);
    if (FAILED(hr)) return hr;
    BOOL muted = FALSE;
    level.canMute = (support & ENDPOINT_HARDWARE_SUPPORT_MUTE) != 0;
    if (level.canMute && FAILED(volume->GetMute(&muted))) muted = FALSE;
    level.muted = muted != FALSE;
    return S_OK;
}

HRESULT writeHardwareVolume(const std::wstring& id, float db, bool mute) {
    ComPtr<IAudioEndpointVolume> volume;
    DWORD support = 0;
    HRESULT hr = hardwareControl(id, volume, support);
    float minDb = 0, maxDb = 0, stepDb = 0;
    if (SUCCEEDED(hr)) hr = volume->GetVolumeRange(&minDb, &maxDb, &stepDb);
    if (SUCCEEDED(hr)) hr = volume->SetMasterVolumeLevel(std::clamp(db, minDb, maxDb), nullptr);
    if (SUCCEEDED(hr) && (support & ENDPOINT_HARDWARE_SUPPORT_MUTE)) hr = volume->SetMute(mute ? TRUE : FALSE, nullptr);
    return hr;
}

}
