# equinox-autoconfigurator
Modified Equinox update configurator that automatically installs and starts bundles in the plugins directory

The original Equinox update configurator is deprecated and replaced by the SimpleConfigurator. To learn more about Equinox Configurator have a look here: https://wiki.eclipse.org/Configurator

Note that this autoconfigurator simply installs and starts bundles in the _plugins_ directory. It does not support p2 features (bundle groups).

The intention of the autoconfigurator is to simplify the creation of a simple Equinox runtime. It needs to be located next to the _org.eclipse.osgi_ bundle. 
The _plugins_ directory needs to be at the same level together with the _configuration_ directory.

somedir/
  configuration/
    config.ini
  plugins/
    B1.jar
    B2.jar
  org.eclipse.osgi_x.x.x.jar
  org.eclipse.equinox.autoconfigurator_x.x.x.jar

The _config.ini_ file only needs the following entries:

```
osgi.bundles=org.eclipse.equinox.autoconfigurator@start
eclipse.ignoreApp=true
```

Launching the application via `java -jar org.eclipse.osgi_x.x.x.jar` will then start the autoconfiguration which scans the _plugins_ directory, installs and start all containing bundles.