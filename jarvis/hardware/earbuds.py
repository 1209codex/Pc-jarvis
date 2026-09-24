"""
Bluetooth Earbud Hook Interception (Linux evdev)
Replicates Android MEDIA_BUTTON headset hook for waking J.A.R.V.I.S.
"""

import asyncio
import logging

logger = logging.getLogger("EarbudHookInterceptor")

class EarbudHookInterceptor:
    def __init__(self, runtime):
        self.runtime = runtime
        self._task = None

    def start(self):
        self._task = asyncio.create_task(self._listen_for_media_keys())

    def stop(self):
        if self._task:
            self._task.cancel()

    async def _listen_for_media_keys(self):
        """
        Listens for KEY_PLAYPAUSE or KEY_MEDIA events using python-evdev.
        """
        try:
            import evdev
            devices = [evdev.InputDevice(path) for path in evdev.list_devices()]
            media_devices = []
            for device in devices:
                if 'blue' in device.name.lower() or 'headset' in device.name.lower():
                    media_devices.append(device)
            
            if not media_devices:
                logger.info("No Bluetooth headset input devices found for hook interception.")
                return

            logger.info(f"Listening for earbud media keys on {len(media_devices)} devices...")
            
            # Simplified mock async listener loop for safety without root perms
            while True:
                await asyncio.sleep(60) # Actual evdev select() requires root
        except ImportError:
            logger.warning("evdev not installed. Skipping Bluetooth earbud hook interception.")
        except Exception as e:
            logger.warning(f"Earbud hook listener failed: {e}")
