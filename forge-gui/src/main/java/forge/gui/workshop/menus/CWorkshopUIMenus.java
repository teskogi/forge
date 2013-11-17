package forge.gui.workshop.menus;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenu;

/**
 * Gets the menus associated with the Game screen.
 *
 */
public class CWorkshopUIMenus {

    private final boolean SHOW_ICONS = true;

    public List<JMenu> getMenus() {
        List<JMenu> menus = new ArrayList<JMenu>();
        menus.add(WorkshopFileMenu.getMenu(SHOW_ICONS));
        return menus;
    }
}
