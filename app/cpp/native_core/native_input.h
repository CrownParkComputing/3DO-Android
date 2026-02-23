/**
 * 4DO Native Core - Input System
 * Controller input handling with remapping support
 */

#ifndef FOURDO_NATIVE_INPUT_H
#define FOURDO_NATIVE_INPUT_H

#include "native_types.h"
#include "native_log.h"
#include <atomic>
#include <array>
#include <string>
#include <map>

namespace fourdo {
namespace core {

/**
 * 3DO Controller Buttons
 * Matches the hardware button layout
 */
enum class Button : u32 {
    Up = 0,
    Down = 1,
    Left = 2,
    Right = 3,
    A = 4,
    B = 5,
    C = 6,
    PlayPause = 7,
    Stop = 8,
    L1 = 9,        // Left shoulder
    R1 = 10,       // Right shoulder
    X = 11,        // FZ-1 specific
    P = 12,        // FZ-10 specific
    
    // Virtual buttons for remapping
    Menu = 13,
    Count = 14
};

/**
 * InputSource - Source of input (physical or mapped)
 */
struct InputSource {
    int device_id;      // Android device ID
    int source_type;    // KEYCODE, AXIS, etc.
    int source_code;    // Key code or axis code
    bool analog;        // Is this an analog axis?
    
    InputSource() : device_id(-1), source_type(0), source_code(0), analog(false) {}
};

/**
 * ControllerState - Current state of all controller inputs
 */
class ControllerState {
    std::atomic<u32> m_buttons{0};
    std::atomic<i16> m_axis_x{0};
    std::atomic<i16> m_axis_y{0};
    
public:
    /**
     * Set button state
     */
    void set_button(Button button, bool pressed) {
        u32 mask = 1 << static_cast<u32>(button);
        if (pressed) {
            m_buttons.fetch_or(mask, std::memory_order_relaxed);
        } else {
            m_buttons.fetch_and(~mask, std::memory_order_relaxed);
        }
    }
    
    /**
     * Get button state
     */
    bool get_button(Button button) const {
        u32 mask = 1 << static_cast<u32>(button);
        return (m_buttons.load(std::memory_order_relaxed) & mask) != 0;
    }
    
    /**
     * Set analog axis values (-32768 to 32767)
     */
    void set_axis(i16 x, i16 y) {
        m_axis_x.store(x, std::memory_order_relaxed);
        m_axis_y.store(y, std::memory_order_relaxed);
    }
    
    /**
     * Get axis X value
     */
    i16 get_axis_x() const {
        return m_axis_x.load(std::memory_order_relaxed);
    }
    
    /**
     * Get axis Y value
     */
    i16 get_axis_y() const {
        return m_axis_y.load(std::memory_order_relaxed);
    }
    
    /**
     * Get all buttons as bitmask
     */
    u32 get_button_mask() const {
        return m_buttons.load(std::memory_order_relaxed);
    }
    
    /**
     * Clear all inputs
     */
    void clear() {
        m_buttons.store(0, std::memory_order_relaxed);
        m_axis_x.store(0, std::memory_order_relaxed);
        m_axis_y.store(0, std::memory_order_relaxed);
    }
    
    /**
     * Convert to 3DO PBUS format
     * Returns the controller data in the format expected by the 3DO
     */
    u32 to_pbus() const {
        u32 pbus = 0;
        
        // PBUS button mapping (matches 3DO hardware)
        if (get_button(Button::Up)) pbus |= (1 << 0);
        if (get_button(Button::Down)) pbus |= (1 << 1);
        if (get_button(Button::Left)) pbus |= (1 << 2);
        if (get_button(Button::Right)) pbus |= (1 << 3);
        if (get_button(Button::A)) pbus |= (1 << 4);
        if (get_button(Button::B)) pbus |= (1 << 5);
        if (get_button(Button::C)) pbus |= (1 << 6);
        if (get_button(Button::PlayPause)) pbus |= (1 << 7);
        if (get_button(Button::Stop)) pbus |= (1 << 8);
        if (get_button(Button::L1)) pbus |= (1 << 9);
        if (get_button(Button::R1)) pbus |= (1 << 10);
        
        // Handle analog to digital conversion for d-pad
        i16 x = get_axis_x();
        i16 y = get_axis_y();
        i16 threshold = 16384;  // ~50% threshold
        
        if (x < -threshold) pbus |= (1 << 2);  // Left
        if (x > threshold) pbus |= (1 << 3);   // Right
        if (y < -threshold) pbus |= (1 << 0);  // Up
        if (y > threshold) pbus |= (1 << 1);   // Down
        
        return pbus;
    }
};

/**
 * InputMapping - Button mapping configuration
 */
class InputMapping {
    std::map<int, Button> m_key_mapping;      // Android keycode -> 3DO button
    std::map<int, Button> m_axis_mapping;     // Android axis -> 3DO button
    int m_axis_x_code;                         // Axis code for analog X
    int m_axis_y_code;                         // Axis code for analog Y
    
public:
    InputMapping() : m_axis_x_code(-1), m_axis_y_code(-1) {
        // Default mapping for standard gamepad
        set_default_mapping();
    }
    
    /**
     * Set default controller mapping
     */
    void set_default_mapping() {
        // Standard Android gamepad mapping
        m_key_mapping = {
            {KEYCODE_DPAD_UP, Button::Up},
            {KEYCODE_DPAD_DOWN, Button::Down},
            {KEYCODE_DPAD_LEFT, Button::Left},
            {KEYCODE_DPAD_RIGHT, Button::Right},
            {KEYCODE_BUTTON_A, Button::A},
            {KEYCODE_BUTTON_B, Button::B},
            {KEYCODE_BUTTON_C, Button::C},
            {KEYCODE_BUTTON_X, Button::A},
            {KEYCODE_BUTTON_Y, Button::B},
            {KEYCODE_BUTTON_Z, Button::C},
            {KEYCODE_BUTTON_START, Button::PlayPause},
            {KEYCODE_BUTTON_SELECT, Button::Stop},
            {KEYCODE_BUTTON_L1, Button::L1},
            {KEYCODE_BUTTON_R1, Button::R1},
            {KEYCODE_BUTTON_THUMBL, Button::Stop},
            {KEYCODE_BUTTON_THUMBR, Button::PlayPause},
        };
        
        m_axis_x_code = AXIS_X;
        m_axis_y_code = AXIS_Y;
    }
    
    /**
     * Map an Android keycode to a 3DO button
     */
    void map_key(int keycode, Button button) {
        m_key_mapping[keycode] = button;
    }
    
    /**
     * Map an Android axis to a 3DO button
     */
    void map_axis(int axis, Button button) {
        m_axis_mapping[axis] = button;
    }
    
    /**
     * Set analog stick axis codes
     */
    void set_analog_axes(int x_axis, int y_axis) {
        m_axis_x_code = x_axis;
        m_axis_y_code = y_axis;
    }
    
    /**
     * Get button for keycode
     */
    Optional<Button> get_button_for_key(int keycode) const {
        auto it = m_key_mapping.find(keycode);
        if (it != m_key_mapping.end()) {
            return it->second;
        }
        return {};
    }
    
    /**
     * Get analog axis codes
     */
    int get_x_axis() const { return m_axis_x_code; }
    int get_y_axis() const { return m_axis_y_code; }
    
    // Android keycodes (subset)
    static constexpr int KEYCODE_DPAD_UP = 19;
    static constexpr int KEYCODE_DPAD_DOWN = 20;
    static constexpr int KEYCODE_DPAD_LEFT = 21;
    static constexpr int KEYCODE_DPAD_RIGHT = 22;
    static constexpr int KEYCODE_BUTTON_A = 96;
    static constexpr int KEYCODE_BUTTON_B = 97;
    static constexpr int KEYCODE_BUTTON_C = 98;
    static constexpr int KEYCODE_BUTTON_X = 99;
    static constexpr int KEYCODE_BUTTON_Y = 100;
    static constexpr int KEYCODE_BUTTON_Z = 101;
    static constexpr int KEYCODE_BUTTON_L1 = 102;
    static constexpr int KEYCODE_BUTTON_R1 = 103;
    static constexpr int KEYCODE_BUTTON_START = 108;
    static constexpr int KEYCODE_BUTTON_SELECT = 109;
    static constexpr int KEYCODE_BUTTON_THUMBL = 106;
    static constexpr int KEYCODE_BUTTON_THUMBR = 107;
    
    // Android axis codes
    static constexpr int AXIS_X = 0;
    static constexpr int AXIS_Y = 1;
    static constexpr int AXIS_Z = 11;
    static constexpr int AXIS_RZ = 14;
    static constexpr int AXIS_HAT_X = 15;
    static constexpr int AXIS_HAT_Y = 16;
};

/**
 * InputSystem - Main input handling class
 */
class InputSystem {
    static constexpr u32 MAX_CONTROLLERS = 8;
    
    std::array<ControllerState, MAX_CONTROLLERS> m_controllers;
    std::array<InputMapping, MAX_CONTROLLERS> m_mappings;
    std::atomic<u32> m_active_controller_mask{1};  // Controller 0 always active
    
public:
    InputSystem() {
        for (auto& mapping : m_mappings) {
            mapping.set_default_mapping();
        }
    }
    
    /**
     * Process a key event from Android
     */
    void on_key_event(int device_id, int keycode, bool pressed) {
        // Find or assign controller slot for this device
        u32 slot = find_controller_slot(device_id);
        if (slot >= MAX_CONTROLLERS) return;
        
        // Look up button mapping
        Optional<Button> button = m_mappings[slot].get_button_for_key(keycode);
        if (button.has_value) {
            m_controllers[slot].set_button(button.value, pressed);
            LOGD("Controller %u: Button %d = %s", slot, 
                 static_cast<int>(button.value), pressed ? "PRESSED" : "RELEASED");
        }
    }
    
    /**
     * Process an axis event from Android
     */
    void on_axis_event(int device_id, int axis, float value) {
        u32 slot = find_controller_slot(device_id);
        if (slot >= MAX_CONTROLLERS) return;
        
        // Convert float (-1.0 to 1.0) to i16
        i16 iValue = static_cast<i16>(value * 32767.0f);
        
        if (axis == m_mappings[slot].get_x_axis()) {
            m_controllers[slot].set_axis(iValue, m_controllers[slot].get_axis_y());
        } else if (axis == m_mappings[slot].get_y_axis()) {
            m_controllers[slot].set_axis(m_controllers[slot].get_axis_x(), iValue);
        }
    }
    
    /**
     * Get controller state for PBUS
     */
    u32 get_controller_state(u32 slot) const {
        if (slot >= MAX_CONTROLLERS) return 0;
        return m_controllers[slot].to_pbus();
    }
    
    /**
     * Get raw controller state
     */
    const ControllerState& get_controller(u32 slot) const {
        static ControllerState empty;
        if (slot >= MAX_CONTROLLERS) return empty;
        return m_controllers[slot];
    }
    
    /**
     * Clear all controller states
     */
    void clear_all() {
        for (auto& controller : m_controllers) {
            controller.clear();
        }
    }
    
    /**
     * Set button mapping for a controller
     */
    void set_mapping(u32 slot, const InputMapping& mapping) {
        if (slot < MAX_CONTROLLERS) {
            m_mappings[slot] = mapping;
        }
    }
    
    /**
     * Get button mapping for a controller
     */
    const InputMapping& get_mapping(u32 slot) const {
        static InputMapping default_mapping;
        if (slot >= MAX_CONTROLLERS) return default_mapping;
        return m_mappings[slot];
    }
    
private:
    /**
     * Find controller slot for device
     * Returns MAX_CONTROLLERS if no slot available
     */
    u32 find_controller_slot(int device_id) {
        // Simple implementation: always use slot 0
        // TODO: Track device IDs and support multiple controllers
        return 0;
    }
};

} // namespace core
} // namespace fourdo

#endif // FOURDO_NATIVE_INPUT_H