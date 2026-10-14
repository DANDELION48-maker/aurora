#include "alsa_output.h"
#include <cerrno>
#include <climits>
#include <iterator>

namespace aurora::alsa {
namespace {

struct Mixer {
    snd_mixer_t* handle = nullptr;
    ~Mixer() { if (handle) snd_mixer_close(handle); }
};

// hw:CARD=X,DEV=n and hw:N,M both name a pcm; the mixer lives on the card's control device
std::string controlOf(const std::string& device) {
    if (device.rfind("hw:", 0) != 0) return {};
    const size_t named = device.find(",DEV=");
    if (named != std::string::npos) return device.substr(0, named);
    return device.substr(0, device.find(','));
}

int openMixer(const std::string& device, Mixer& mixer) {
    const std::string control = controlOf(device);
    if (control.empty()) return -EINVAL;
    int error = snd_mixer_open(&mixer.handle, 0);
    if (error < 0) return error;
    if ((error = snd_mixer_attach(mixer.handle, control.c_str())) < 0) return error;
    if ((error = snd_mixer_selem_register(mixer.handle, nullptr, nullptr)) < 0) return error;
    return snd_mixer_load(mixer.handle);
}

// a playback volume with a dB scale; usb dacs often expose several, so the usual names win
snd_mixer_elem_t* volumeElement(snd_mixer_t* mixer) {
    static const char* const preferred[] = {"PCM", "Master", "Speaker", "Headphone", "Digital"};
    snd_mixer_elem_t* best = nullptr;
    size_t bestRank = SIZE_MAX;
    for (snd_mixer_elem_t* element = snd_mixer_first_elem(mixer); element; element = snd_mixer_elem_next(element)) {
        if (!snd_mixer_selem_is_active(element) || !snd_mixer_selem_has_playback_volume(element)) continue;
        long minDb = 0, maxDb = 0;
        if (snd_mixer_selem_get_playback_dB_range(element, &minDb, &maxDb) < 0 || maxDb <= minDb) continue;
        const char* name = snd_mixer_selem_get_name(element);
        size_t rank = std::size(preferred);
        for (size_t i = 0; name && i < std::size(preferred); i++) {
            if (std::strcmp(name, preferred[i]) == 0) {
                rank = i;
                break;
            }
        }
        if (rank < bestRank) {
            bestRank = rank;
            best = element;
        }
    }
    return best;
}

}

int readHardwareVolume(const std::string& device, HardwareLevel& level) {
    Mixer mixer;
    int error = openMixer(device, mixer);
    if (error < 0) return error;
    snd_mixer_elem_t* element = volumeElement(mixer.handle);
    if (!element) return -ENOENT;
    if ((error = snd_mixer_selem_get_playback_dB_range(element, &level.minCentiDb, &level.maxCentiDb)) < 0) return error;
    if ((error = snd_mixer_selem_get_playback_dB(element, SND_MIXER_SCHN_FRONT_LEFT, &level.centiDb)) < 0) return error;
    level.canMute = snd_mixer_selem_has_playback_switch(element) != 0;
    int on = 1;
    if (level.canMute && snd_mixer_selem_get_playback_switch(element, SND_MIXER_SCHN_FRONT_LEFT, &on) < 0) on = 1;
    level.muted = on == 0;
    return 0;
}

int writeHardwareVolume(const std::string& device, long centiDb, bool mute) {
    Mixer mixer;
    int error = openMixer(device, mixer);
    if (error < 0) return error;
    snd_mixer_elem_t* element = volumeElement(mixer.handle);
    if (!element) return -ENOENT;
    long minDb = 0, maxDb = 0;
    if ((error = snd_mixer_selem_get_playback_dB_range(element, &minDb, &maxDb)) < 0) return error;
    // rounds toward the quieter step so a request never ends up louder than asked
    if ((error = snd_mixer_selem_set_playback_dB_all(element, std::clamp(centiDb, minDb, maxDb), -1)) < 0) return error;
    if (snd_mixer_selem_has_playback_switch(element) && (error = snd_mixer_selem_set_playback_switch_all(element, mute ? 0 : 1)) < 0) return error;
    return 0;
}

}
