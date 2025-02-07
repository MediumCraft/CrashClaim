package net.crashcraft.crashclaim;

import co.aikar.taskchain.BukkitTaskChainFactory;
import co.aikar.taskchain.TaskChain;
import co.aikar.taskchain.TaskChainFactory;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.lib.PaperLib;
import net.crashcraft.crashclaim.api.CrashClaimAPI;
import net.crashcraft.crashclaim.commands.CommandManager;
import net.crashcraft.crashclaim.commands.claiming.ClaimCommand;
import net.crashcraft.crashclaim.config.ConfigManager;
import net.crashcraft.crashclaim.config.GlobalConfig;
import net.crashcraft.crashclaim.listeners.PacketEventsListener;
import net.crashcraft.crashclaim.payment.PaymentProcessor;
import net.crashcraft.crashclaim.payment.PaymentProvider;
import net.crashcraft.crashclaim.payment.ProcessorManager;
import net.crashcraft.crashclaim.payment.ProviderInitializationException;
import net.crashcraft.crashclaim.crashutils.CrashUtils;
import net.crashcraft.crashclaim.crashutils.menusystem.GUI;
import net.crashcraft.crashclaim.data.ClaimDataManager;
import net.crashcraft.crashclaim.data.MaterialName;
import net.crashcraft.crashclaim.listeners.PaperListener;
import net.crashcraft.crashclaim.listeners.PlayerListener;
import net.crashcraft.crashclaim.listeners.WorldListener;
import net.crashcraft.crashclaim.localization.LocalizationLoader;
import net.crashcraft.crashclaim.migration.MigrationManager;
import net.crashcraft.crashclaim.nms.NMSHandler;
import net.crashcraft.crashclaim.nms.NMSManager;
import net.crashcraft.crashclaim.permissions.PermissionHelper;
import net.crashcraft.crashclaim.pluginsupport.PluginSupport;
import net.crashcraft.crashclaim.pluginsupport.PluginSupportManager;
import net.crashcraft.crashclaim.visualize.VisualizationManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;

public class CrashClaim extends JavaPlugin {
    private static CrashClaim plugin;

    private boolean dataLoaded = false;

    private CrashClaimAPI api;

    private NMSHandler handler;
    private PluginSupportManager pluginSupport;

    private ClaimDataManager manager;
    private VisualizationManager visualizationManager;
    private CrashUtils crashUtils;
    private MaterialName materialName;
    private PaymentProcessor payment;
    private CommandManager commandManager;
    private MigrationManager migrationManager;
    private BukkitAudiences adventure;

    @Override
    public void onLoad() {
        plugin = this;

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        //Are all listeners read only?
        PacketEvents.getAPI().getSettings().reEncodeByDefault(false)
                .checkForUpdates(false)
                .bStats(true);
        PacketEvents.getAPI().load();


        this.crashUtils = new CrashUtils(this);
        this.pluginSupport = new PluginSupportManager(this); // Enable plugin support
        this.pluginSupport.onLoad(); // Load all plugin support
    }

    @Override
    public void onEnable() {
        try (InputStream stream = this.getResource("notes")) {
            if (stream != null && stream.available() > 0) {
            
            } else {
                getPluginLoader().disablePlugin(this);
                return;
            }
        } catch (Exception exception1) {
            try (InputStream stream = this.getResource("notes.txt")) {
                if (stream != null && stream.available() > 0) {
        
                } else {
                   getPluginLoader().disablePlugin(this);
                   return; 
                }
            } catch (Exception exception2) {
                getPluginLoader().disablePlugin(this);
                return;
            }
        }
        
        Bukkit.getPluginManager().registerEvents(pluginSupport, this);

        taskChainFactory = BukkitTaskChainFactory.create(this);
        this.adventure = BukkitAudiences.create(this);

        loadConfigs();

        handler = new NMSManager().getHandler(); // Find and fetch version wrapper

        getLogger().info("Loading language file");
        LocalizationLoader.initialize(); // Init and reload localization
        getLogger().info("Finished loading language file");

        crashUtils.setupMenuSubSystem();
        crashUtils.setupTextureCache();

        payment = setupPaymentProvider(this, GlobalConfig.paymentProvider).getProcessor();

        this.visualizationManager = new VisualizationManager(this);
        this.manager = new ClaimDataManager(this);
        this.materialName = new MaterialName();

        this.dataLoaded = true;

        new PermissionHelper(manager);

        this.migrationManager = new MigrationManager(this);
        commandManager = new CommandManager(this);

        Bukkit.getPluginManager().registerEvents(new WorldListener(manager, visualizationManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(manager, visualizationManager), this);

        PacketEvents.getAPI().getEventManager().registerListener(new PacketEventsListener(plugin, new ClaimCommand(getDataManager(), getVisualizationManager())),
                PacketListenerPriority.LOW);
        PacketEvents.getAPI().init();

        if (PaperLib.isPaper()) {
            getLogger().info("Using extra protections provided by the paper api");
            Bukkit.getPluginManager().registerEvents(new PaperListener(manager, visualizationManager), this);
        } else {
            getLogger().info("Looks like your not running paper, some protections will be disabled");
            PaperLib.suggestPaper(this);
        }

        pluginSupport.onEnable();
        LocalizationLoader.register(); // Register PlaceHolders

        Bukkit.getServicesManager().register(PaymentProvider.class, payment.getProvider(), plugin, ServicePriority.Normal);

        String bukkitVersion = Bukkit.getBukkitVersion();
        if (!bukkitVersion.matches("1\\.20\\.\\d+.*")) {
            getLogger().severe("Incompatible server version: " + bukkitVersion);
            getServer().getPluginManager().disablePlugin(this);
        }

        this.api = new CrashClaimAPI(this); // Enable api last as it might require some instances before to function properly.
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this); // Stop saving tasks

        if (dataLoaded) {
            manager.saveClaimsSync(); // Do a force save to make sure all claims are saved as we just unregistered the save task
            manager.cleanupAndClose(); // freezes claim saving and cleans up memory references to claims
        }

        //Unregister all user facing things
        HandlerList.unregisterAll(this);
        commandManager.getCommandManager().unregisterCommands();
        for (Player player : Bukkit.getOnlinePlayers()){
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GUI){
                player.closeInventory();
            }
        }

        pluginSupport.onDisable();

        //Null all references just to be sure, manager will still hold them but this stops this class from being referenced for anything
        dataLoaded = false;
        plugin = null;
        api = null;
        manager = null;
        visualizationManager = null;
        crashUtils = null;
        materialName = null;
        payment = null;
        commandManager = null;
        migrationManager = null;
        adventure = null;
    }
    public ProcessorManager setupPaymentProvider(JavaPlugin plugin){
        return setupPaymentProvider(plugin, "");
    }
    public ProcessorManager setupPaymentProvider(JavaPlugin plugin, String providerOverride){
        try {
            return new ProcessorManager(plugin, providerOverride);
        } catch (ProviderInitializationException e){
            e.printStackTrace();
        }
        return null;
    }

    public void loadConfigs(){
        File dataFolder = plugin.getDataFolder();

        if (dataFolder.mkdirs()){
            getLogger().info("Created data directory");
        }

        try {
            getLogger().info("Loading configs");
            if (!new File(dataFolder, "lookup.yml").exists()) {
                plugin.saveResource("lookup.yml", false);
            }
            ConfigManager.initConfig(new File(dataFolder, "config.yml"), GlobalConfig.class);

            getLogger().info("Finished loading base configs");
        } catch (Exception ex){
            ex.printStackTrace();
            getLogger().severe("Could not load configuration properly. Stopping server");
            plugin.getServer().shutdown();
        }
    }

    public void disablePlugin(String error){
        getLogger().severe(error);
        Bukkit.getPluginManager().disablePlugin(this);
    }

    private static TaskChainFactory taskChainFactory;
    public static <T> TaskChain<T> newChain() {
        return taskChainFactory.newChain();
    }
    public static <T> TaskChain<T> newSharedChain(String name) {
        return taskChainFactory.newSharedChain(name);
    }

    public static CrashClaim getPlugin() {
        return plugin;
    }

    public ClaimDataManager getDataManager() {
        return manager;
    }

    public VisualizationManager getVisualizationManager() {
        return visualizationManager;
    }

    public CrashUtils getCrashUtils() {
        return crashUtils;
    }

    public PaymentProcessor getPayment() {
        return payment;
    }

    public MaterialName getMaterialName() {
        return materialName;
    }

    public CrashClaimAPI getApi() {
        return api;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }


    public MigrationManager getMigrationManager() {
        return migrationManager;
    }

    public BukkitAudiences getAdventure() {
        return adventure;
    }

    public NMSHandler getHandler() {
        return handler;
    }

    public PluginSupport getPluginSupport(){
        return pluginSupport.getSupportDistributor();
    }

    public PluginSupportManager getPluginSupportManager() {
        return pluginSupport;
    }
}
