package de.iolite.apps.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.Validate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.iolite.api.IOLITEAPINotResolvableException;
import de.iolite.api.IOLITEAPIProvider;
import de.iolite.api.IOLITEPermissionDeniedException;
import de.iolite.api.heating.access.HeatingAPI;
import de.iolite.api.heating.access.PlaceSchedule;
import de.iolite.app.AbstractIOLITEApp;
import de.iolite.app.api.device.DeviceAPIException;
import de.iolite.app.api.device.access.Device;
import de.iolite.app.api.device.access.DeviceAPI;
import de.iolite.app.api.device.access.DeviceAPI.DeviceAPIObserver;
import de.iolite.app.api.device.access.DeviceBooleanProperty;
import de.iolite.app.api.device.access.DeviceBooleanProperty.DeviceBooleanPropertyObserver;
import de.iolite.app.api.device.access.DeviceDoubleProperty;
import de.iolite.app.api.environment.EnvironmentAPI;
import de.iolite.app.api.environment.Location;
import de.iolite.app.api.frontend.FrontendAPI;
import de.iolite.app.api.frontend.FrontendAPIException;
import de.iolite.app.api.frontend.util.FrontendAPIRequestHandler;
import de.iolite.app.api.frontend.util.FrontendAPIUtility;
import de.iolite.app.api.storage.StorageAPI;
import de.iolite.app.api.storage.StorageAPIException;
import de.iolite.app.api.user.access.UserAPI;
import de.iolite.apps.example.ViewRegistrator.ResourcePackageConfig;
import de.iolite.apps.example.ViewRegistrator.TemplateConfig;
import de.iolite.apps.example.controller.EnvironmentController;
import de.iolite.apps.example.devices.SonosController;
import de.iolite.apps.example.internals.PageWithEmbeddedSessionTokenRequestHandler;
import de.iolite.common.lifecycle.exception.CleanUpFailedException;
import de.iolite.common.lifecycle.exception.InitializeFailedException;
import de.iolite.common.lifecycle.exception.StartFailedException;
import de.iolite.common.lifecycle.exception.StopFailedException;
import de.iolite.common.requesthandler.HTTPStatus;
import de.iolite.common.requesthandler.IOLITEHTTPRequest;
import de.iolite.common.requesthandler.IOLITEHTTPRequestHandler;
import de.iolite.common.requesthandler.IOLITEHTTPResponse;
import de.iolite.common.requesthandler.IOLITEHTTPStaticResponse;
import de.iolite.common.requesthandler.StaticResources;
import de.iolite.common.requesthandler.StaticResources.PathHandlerPair;
import de.iolite.data.DailyEvents;
import de.iolite.data.GoogleData;
import de.iolite.drivers.basic.DriverConstants;
import de.iolite.insys.mirror.api.MirrorApiException;
import de.iolite.utilities.concurrency.scheduler.Scheduler;
import de.iolite.utilities.disposeable.Disposeable;
import de.iolite.utilities.time.series.DataEntries.AggregatedEntry;
import de.iolite.utilities.time.series.DataEntries.BooleanEntry;
import de.iolite.utilities.time.series.Function;
import de.iolite.utilities.time.series.TimeInterval;

/**
 * <code>ExampleApp</code> is an example IOLITE App.
 *
 * @author Grzegorz Lehmann
 * @author Erdene-Ochir Tuguldur
 * @author Felix Rodemund edit by Group Calendar Integration
 * @since 1.0
 */
public final class CalendarIntegrationAppMain extends AbstractIOLITEApp {

	private static final class DeviceJSONRequestHandler extends FrontendAPIRequestHandler {

		@Override
		protected IOLITEHTTPResponse handleRequest(final IOLITEHTTPRequest request, final String subPath) {
			final JSONArray deviceIdentifiers = new JSONArray();

			final JSONObject response = new JSONObject();
			response.put("identifiers", deviceIdentifiers);
			return new IOLITEHTTPStaticResponse(response.toString(), HTTPStatus.OK,
					IOLITEHTTPResponse.JSON_CONTENT_TYPE);
		}
	}

	private static final class DeviceAddAndRemoveLogger implements DeviceAPIObserver {

		@Override
		public void addedToDevices(final Device device) {
			LOGGER.debug("a new device added '{}'", device.getIdentifier());
		}

		@Override
		public void removedFromDevices(final Device device) {
			LOGGER.debug("a device removed '{}'", device.getIdentifier());
		}
	}

	// private static final class DeviceOnOffStatusLogger implements
	// DeviceBooleanPropertyObserver {
	//
	// @Nonnull
	// private final String identifier;
	//
	// private DeviceOnOffStatusLogger(final String deviceIdentifier) {
	// this.identifier = Validate.notNull(deviceIdentifier, "'deviceIdentifier'
	// must not be null");
	// }
	//
	// @Override
	// public void deviceChanged(final Device element) {
	// // nothing here
	// }
	//
	// @Override
	// public void keyChanged(final String key) {
	// // nothing here
	// }
	//
	// @Override
	// public void valueChanged(final Boolean value) {
	// if (value) {
	// LOGGER.debug("device '{}' turned on", this.identifier);
	// }
	// else {
	// LOGGER.debug("device '{}' turned off", this.identifier);
	// }
	// }
	// }

	/**
	 * A response handler returning devices filtered by the property type.
	 */
	class DevicesResponseHandler extends FrontendAPIRequestHandler {

		@Override
		protected IOLITEHTTPResponse handleRequest(final IOLITEHTTPRequest request, final String subPath) {
			String propertyType;
			try {
				propertyType = new JSONObject(readPassedData(request)).getString("propertyType");
			} catch (final JSONException e) {
				LOGGER.error("Could not handle devices request due to a JSON error: {}", e.getMessage(), e);
				return new IOLITEHTTPStaticResponse(e.getMessage(), HTTPStatus.BadRequest, "text/plain");
			} catch (final IOException e) {
				LOGGER.error("Could not handle devices request due to an I/O error: {}", e.getMessage(), e);
				return new IOLITEHTTPStaticResponse(e.getMessage(), HTTPStatus.BadRequest, "text/plain");
			}

			final JSONArray jsonDeviceArray = new JSONArray();
			for (final Device device : CalendarIntegrationAppMain.this.deviceAPI.getDevices()) {
				if (device.getProperty(propertyType) != null) {
					// device has the correct property type
					final JSONObject jsonDeviceObject = new JSONObject();
					jsonDeviceObject.put("name", device.getName());
					jsonDeviceObject.put("identifier", device.getIdentifier());
					jsonDeviceArray.put(jsonDeviceObject);
				}
			}

			final JSONObject response = new JSONObject();
			response.put("devices", jsonDeviceArray);
			return new IOLITEHTTPStaticResponse(response.toString(), IOLITEHTTPResponse.JSON_CONTENT_TYPE);
		}

		private String getCharset(final IOLITEHTTPRequest request) {
			final String charset = request.getCharset();
			return charset == null || charset.length() == 0 ? IOLITEHTTPStaticResponse.ENCODING_UTF8 : charset;
		}

		private String readPassedData(final IOLITEHTTPRequest request) throws IOException {
			final String charset = getCharset(request);
			try (BufferedReader bufferedReader = new BufferedReader(
					new InputStreamReader(request.getContent(), charset))) {
				final StringBuilder stringBuilder = new StringBuilder();
				String line;
				while ((line = bufferedReader.readLine()) != null) {
					stringBuilder.append(line);
				}
				return stringBuilder.toString();
			}
		}
	}

	/**
	 * A response handler for returning the "not found" response.
	 */
	static class NotFoundResponseHandler extends FrontendAPIRequestHandler {

		@Override
		protected IOLITEHTTPResponse handleRequest(final IOLITEHTTPRequest request, final String subPath) {
			return new IOLITEHTTPStaticResponse(HTTPStatus.NotFound, IOLITEHTTPResponse.HTML_CONTENT_TYPE);
		}
	}

	/**
	 * A response handler returning all rooms as JSON array.
	 */
	class RoomsResponseHandler extends FrontendAPIRequestHandler {

		@Override
		protected IOLITEHTTPResponse handleRequest(final IOLITEHTTPRequest request, final String subPath) {
			final JSONArray locationNames = new JSONArray();
			for (final Location location : CalendarIntegrationAppMain.this.environmentAPI.getLocations()) {
				locationNames.put(location.getName());
			}
			final JSONObject response = new JSONObject();
			response.put("rooms", locationNames);
			return new IOLITEHTTPStaticResponse(response.toString(), IOLITEHTTPResponse.JSON_CONTENT_TYPE);
		}
	}

	@Nonnull
	private static final Logger LOGGER = LoggerFactory.getLogger(CalendarIntegrationAppMain.class);

	/* App APIs */
	private FrontendAPI frontendAPI;
	private StorageAPI storageAPI;
	private DeviceAPI deviceAPI;
	private EnvironmentAPI environmentAPI;
	private UserAPI userAPI;

	private HeatingAPI heatingAPI;

	/** front end assets */
	private Disposeable disposeableAssets;
	
	/** Sonos assets */
	private final SonosController sonosController = new SonosController();
	private Scheduler scheduler;

	/** Mirror variables */

	private static final String MSG_ERR_RETRIEVE_USERAPI = "Could not retrieve instance of UserAPI!";

	private static final String HTML_RESOURCES = "assets/html/";
	private static final String VIEW_RESOURCES = "assets/views/";
	private static final String APP_ID = "de.iolite.apps.example.CalendarIntegrationAppMain";

	private static final String VIEW_ID_CALENDAR = "CalendarView";
	private static final String ICON_RESPATH_CALENDAR = VIEW_RESOURCES + "calendar-icon.jpg";
	private static final String VIEW_RESPATH_CALENDAR = VIEW_RESOURCES + "calendar.html";
	private static final String VIEW_TEMPLATE_CALENDAR = VIEW_RESOURCES + "calendar.template";
	private static final String VIEW_WEBPATH_CALENDAR = "calendar.html";

	private static final String VIEW_ID_CLOCK = "DateTimeView";
	private static final String ICON_RESPATH_CLOCK = VIEW_RESOURCES + "clock-icon.jpg";
	private static final String VIEW_RESPATH_CLOCK = VIEW_RESOURCES + "clock.html";

	private static final String VIEW_ID_WEATHER = "WeatherView";
	private static final String ICON_RESPATH_WEATHER = VIEW_RESOURCES + "weather-icon.jpg";
	private static final String VIEW_RESPATH_WEATHER = VIEW_RESOURCES + "weather.html";
	private static final String VIEW_WEBPATH_WEATHER = "weather.html";

	DailyEvents calendar = null;

	private ViewRegistrator viewRegistrator;

	String temperature = "0";

	//private ScheduledFuture<?> calendarUpdateThread = null;

	/**
	 * <code>ExampleApp</code> constructor. An IOLITE App must have a public,
	 * parameter-less constructor.
	 */
	public CalendarIntegrationAppMain() {
		// empty
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void cleanUpHook() throws CleanUpFailedException {
		LOGGER.debug("Cleaning");
		LOGGER.debug("Cleaned");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void initializeHook() throws InitializeFailedException {
		LOGGER.debug("Initializing");
		LOGGER.debug("Initialized");

		GoogleData data = new GoogleData();
		try {
			calendar = data.getData(sonosController);
			LOGGER.warn(calendar.toString());
		} catch (IOException | ParseException | GeneralSecurityException | URISyntaxException e) {
			LOGGER.error("ERROR calendar.getData()");
			e.printStackTrace();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void startHook(@Nonnull final IOLITEAPIProvider context) throws StartFailedException {
		// here the IOLITE App is started
		// the context gives access to IOLITE App APIs
		LOGGER.debug("Starting");

		try {		
			// Scheduler
			this.scheduler = context.getScheduler();
			
			// use User API
			this.userAPI = context.getAPI(UserAPI.class);
			LOGGER.debug("Running for user '{}' with locale '{}'", this.userAPI.getUser().getIdentifier(),
					this.userAPI.getUser().getLocale());

			// Storage API enables the App to store data persistently
			// whatever is stored via the storage API will also be available if
			// the App is restarted
			this.storageAPI = context.getAPI(StorageAPI.class);
			initializeStorage();

			// Frontend API enables the App to expose a user interface
			this.frontendAPI = context.getAPI(FrontendAPI.class);
			initializeWebResources();

			// Device API gives access to devices connected to IOLITE
			this.deviceAPI = context.getAPI(DeviceAPI.class);
			initializeDeviceManager();

			// Environment API gives a access for rooms, current situation etc.
			this.environmentAPI = context.getAPI(EnvironmentAPI.class);
			initializeEnvironment();

			// Heating API
			this.heatingAPI = context.getAPI(HeatingAPI.class);

			// getting IOLTE user-ID
			final UserAPI userApi;
			final String userId;
			try {
				userApi = context.getAPI(UserAPI.class);
				userId = userApi.getUser().getIdentifier();
			} catch (final IOLITEAPINotResolvableException | IOLITEPermissionDeniedException e) {
				LOGGER.error(MSG_ERR_RETRIEVE_USERAPI, e);
				throw new StartFailedException(MSG_ERR_RETRIEVE_USERAPI, e);
			}

			final ResourcePackageConfig staticResourceConfig = new ResourcePackageConfig(VIEW_RESOURCES);
			staticResourceConfig.addView(VIEW_ID_CLOCK, VIEW_RESPATH_CLOCK, ICON_RESPATH_CLOCK);
			staticResourceConfig.addView(VIEW_ID_WEATHER, VIEW_RESPATH_WEATHER, ICON_RESPATH_WEATHER);
			staticResourceConfig.addView(VIEW_ID_CALENDAR, VIEW_RESPATH_CALENDAR, ICON_RESPATH_CALENDAR);
			this.viewRegistrator = new ViewRegistrator(staticResourceConfig, APP_ID, userId);
			deviceAPI.setObserver(this.viewRegistrator);
			deviceAPI.getDevices().forEach(this.viewRegistrator::addedToDevices);

			scheduler.scheduleAtFixedRate(() -> {
				try {
					GoogleData calendar_data = new GoogleData();
					CalendarIntegrationAppMain.this.calendar = calendar_data.getData(sonosController);
					final TemplateConfig templateConf_calendar = new TemplateConfig(VIEW_TEMPLATE_CALENDAR,
							VIEW_WEBPATH_CALENDAR, VIEW_ID_CALENDAR);
					templateConf_calendar.putReplacement("{CALENDAR}",
							CalendarIntegrationAppMain.this.calendar.toString());
					CalendarIntegrationAppMain.this.viewRegistrator.updateTemplatePage(templateConf_calendar);

					final TemplateConfig templateConf_weather = new TemplateConfig(VIEW_RESPATH_WEATHER,
							VIEW_WEBPATH_WEATHER, VIEW_ID_WEATHER);
					refreshWeather();
					templateConf_weather.putReplacement("{WEATHER}", temperature + " &deg C");
					CalendarIntegrationAppMain.this.viewRegistrator.updateTemplatePage(templateConf_weather);
				} catch (final MirrorApiException e) {
					LOGGER.error("Could not create views!", e);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (GeneralSecurityException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (URISyntaxException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}, 0, 1, TimeUnit.MINUTES);

			LOGGER.debug("Mirror Views got registered!");

		} catch (final IOLITEAPINotResolvableException e) {
			throw new StartFailedException(
					MessageFormat.format("Start failed due to required but not resolvable AppAPI: {0}", e.getMessage()),
					e);
		} catch (final IOLITEPermissionDeniedException e) {
			throw new StartFailedException(MessageFormat
					.format("Start failed due to permission denied problems in the examples: {0}", e.getMessage()), e);
		} catch (final StorageAPIException | FrontendAPIException e) {
			throw new StartFailedException(
					MessageFormat.format("Start failed due to an error in the App API examples: {0}", e.getMessage()),
					e);
		}

		LOGGER.debug("Started");
	}

	/**
	 * Updates the weather information for calendar scheduler
	 * 
	 * @return a String containing the current temperatur
	 */
	private void refreshWeather() {
		for (final Device device : this.deviceAPI.getDevices()) {

			LOGGER.warn("Update Weather");

			// Get Weather
			if (device.getProfileIdentifier().equals(DriverConstants.PROFILE_WeatherStation_ID)) {
				
				DeviceDoubleProperty temp = device
						.getDoubleProperty(DriverConstants.PROPERTY_outsideEnvironmentTemperature_ID);

				if (temp != null && temp.getValue().toString() != null) {
					LOGGER.warn("DIE TEMPERATUR IST:  '{}'", temp.getValue());
					temperature = temp.getValue().toString();
				}
			}
		}
		
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void stopHook() throws StopFailedException {
		LOGGER.debug("Stopping");

		// deregister the static assets
		if (this.disposeableAssets != null) {
			this.disposeableAssets.dispose();
		}
		//this.calendarUpdateThread.cancel(false);
		LOGGER.debug("Stopped");
	}

	/**
	 * Example method showing how to use the Device API.
	 */
	private void initializeDeviceManager() {
		// register a device observer
		this.deviceAPI.setObserver(new DeviceAddAndRemoveLogger());
		
		for (final Device device : this.deviceAPI.getDevices()) {
			
			if (device.getProfileIdentifier().equals(DriverConstants.PROFILE_MediaPlayerDevice_ID)){
				sonosController.setSonos(device, scheduler);
			}
			
		}
		
		// go through all devices
		for (final Device device : this.deviceAPI.getDevices()) {

			// final DeviceBooleanProperty onProperty =
			// device.getBooleanProperty(DriverConstants.PROPERTY_on_ID);

			LOGGER.warn("Devices known'{}'", device.getName());

			// Get Weather
			if (device.getProfileIdentifier().equals(DriverConstants.PROFILE_WeatherStation_ID)) {
				LOGGER.warn("ItemWeatherStation");
				DeviceDoubleProperty temp = device
						.getDoubleProperty(DriverConstants.PROPERTY_outsideEnvironmentTemperature_ID);

				if (temp != null && temp.getValue().toString() != null) {
					LOGGER.warn("DIE TEMPERATUR IST:  '{}'", temp.getValue());
					temperature = temp.getValue().toString();
				}
			}

		}
	}

	/**
	 * Example method showing how to use the Storage API.
	 *
	 * @throws StorageAPIException
	 */
	private void initializeStorage() throws StorageAPIException {
		// basically the Storage API provides a key/value storage for different
		// data types
		// save an integer under the key 'test'
		this.storageAPI.saveInt("test", 10);
		// now let's store a string
		this.storageAPI.saveString("some key", "some value");
		// LOGGER.debug("loading 'mirror' from storage: {}",
		// String.valueOf(this.storageAPI.loadString("Mirror")));
		// log the value of an entry, just to demonstrate
		LOGGER.debug("loading 'test' from storage: {}", Integer.valueOf(this.storageAPI.loadInt("test")));
	}
	
	/** Initializes the EnvironmentController class.*/
	private final void initializeEnvironment(){
		
		final EnvironmentController environmentController = new EnvironmentController(this.environmentAPI);
		
	}

	/**
	 * Registering web resources.
	 *
	 * @throws FrontendAPIException
	 *             if some resources are not found.
	 */
	private final void initializeWebResources() throws FrontendAPIException {

		// go through static assets and register them
		final Map<URI, PathHandlerPair> assets = StaticResources.scanClasspath("assets", getClass().getClassLoader());
		this.disposeableAssets = FrontendAPIUtility.registerPublicHandlers(this.frontendAPI, assets);

		// index page
		final IOLITEHTTPRequestHandler indexPageRequestHandler = new PageWithEmbeddedSessionTokenRequestHandler(
				loadTemplate("assets/index.html"));
		this.frontendAPI.registerRequestHandler("", indexPageRequestHandler);
		this.frontendAPI.registerRequestHandler("index.html", indexPageRequestHandler);

		// default handler returning a not found status
		this.frontendAPI.registerDefaultRequestHandler(new NotFoundResponseHandler());

		// example JSON request handlers
		this.frontendAPI.registerRequestHandler("rooms", new RoomsResponseHandler());
		this.frontendAPI.registerRequestHandler("devices", new DevicesResponseHandler());

		this.frontendAPI.registerRequestHandler("get_devices.json", new DeviceJSONRequestHandler());
	}

	/**
	 * Load a HTML template as string.
	 */
	private String loadTemplate(final String templateResource) {
		try {
			return StaticResources.loadResource(templateResource, getClass().getClassLoader());
		} catch (final IOException e) {
			throw new InitializeFailedException("Loading templates for the dummy app failed", e);
		}
	}

}
