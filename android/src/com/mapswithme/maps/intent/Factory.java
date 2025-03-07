package com.mapswithme.maps.intent;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import com.mapswithme.maps.DownloadResourcesLegacyActivity;
import com.mapswithme.maps.Framework;
import com.mapswithme.maps.MapFragment;
import com.mapswithme.maps.MwmActivity;
import com.mapswithme.maps.MwmApplication;
import com.mapswithme.maps.api.Const;
import com.mapswithme.maps.api.ParsedMwmRequest;
import com.mapswithme.maps.api.ParsedRoutingData;
import com.mapswithme.maps.api.ParsedSearchRequest;
import com.mapswithme.maps.api.ParsingResult;
import com.mapswithme.maps.api.RoutePoint;
import com.mapswithme.maps.bookmarks.data.BookmarkManager;
import com.mapswithme.maps.bookmarks.data.FeatureId;
import com.mapswithme.maps.bookmarks.data.MapObject;
import com.mapswithme.maps.location.LocationHelper;
import com.mapswithme.maps.routing.RoutingController;
import com.mapswithme.maps.search.SearchActivity;
import com.mapswithme.maps.search.SearchEngine;
import com.mapswithme.util.KeyValue;
import com.mapswithme.util.StorageUtils;
import com.mapswithme.util.Utils;
import com.mapswithme.util.concurrency.ThreadPool;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class Factory
{
  public static class GeoIntentProcessor implements IntentProcessor
  {
    @Nullable
    @Override
    public MapTask process(@NonNull Intent intent)
    {
      final Uri uri = intent.getData();
      if (uri == null)
        return null;
      final String scheme = intent.getScheme();
      if (!"geo".equals(scheme) && !"ge0".equals(scheme) && !"om".equals(scheme) && !"mapsme".equals(scheme))
        return null;
      return new OpenUrlTask(uri.toString());
    }
  }

  public static class HttpGeoIntentProcessor implements IntentProcessor
  {
    @Nullable
    @Override
    public MapTask process(@NonNull Intent intent)
    {
      final Uri uri = intent.getData();
      if (uri == null)
        return null;
      final String scheme = intent.getScheme();
      if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        return null;
      final String host = uri.getHost();
      if (!"omaps.app".equalsIgnoreCase(host) && !"ge0.me".equalsIgnoreCase(host))
        return null;
      if (uri.getPath() == null)
        return null;
      final String ge0Url = "om:/" + uri.getPath();
      return new OpenUrlTask(ge0Url);
    }
  }

  /**
   * Use this to invoke API task.
   */
  public static class MapsWithMeIntentProcessor implements IntentProcessor
  {
    @Nullable
    @Override
    public MapTask process(@NonNull Intent intent)
    {
      if (!Const.ACTION_MWM_REQUEST.equals(intent.getAction()))
        return null;

      final String apiUrl = intent.getStringExtra(Const.EXTRA_URL);
      if (apiUrl != null)
      {
        SearchEngine.INSTANCE.cancelInteractiveSearch();

        final ParsedMwmRequest request = ParsedMwmRequest.extractFromIntent(intent);
        ParsedMwmRequest.setCurrentRequest(request);

        if (!ParsedMwmRequest.isPickPointMode())
          return new OpenUrlTask(apiUrl);
      }

      throw new AssertionError("Url must be provided!");
    }
  }

  public static class HttpMapsIntentProcessor implements IntentProcessor
  {
    @Nullable
    @Override
    public MapTask process(@NonNull Intent intent)
    {
      final Uri uri = intent.getData();
      if (uri == null)
        return null;
      final String scheme = intent.getScheme();
      if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        return null;
      if (uri.getHost() == null)
        return null;
      final String host = uri.getHost().toLowerCase();
      if (!host.contains("google") && !host.contains("2gis") && !host.contains("openstreetmap"))
        return null;
      return new OpenHttpMapsUrlTask(uri.toString());
    }
  }

  public static class OpenCountryTaskProcessor implements IntentProcessor
  {
    @Nullable
    @Override
    public MapTask process(@NonNull Intent intent)
    {
      if (!intent.hasExtra(DownloadResourcesLegacyActivity.EXTRA_COUNTRY))
        return null;
      String countryId = intent.getStringExtra(DownloadResourcesLegacyActivity.EXTRA_COUNTRY);
      return new ShowCountryTask(countryId);
    }
  }

  public static class KmzKmlProcessor implements IntentProcessor
  {
    @NonNull
    private final DownloadResourcesLegacyActivity mActivity;

    public KmzKmlProcessor(@NonNull DownloadResourcesLegacyActivity activity)
    {
      mActivity = activity;
    }

    @Nullable
    @Override
    public MapTask process(@NonNull Intent intent)
    {
      // See KML/KMZ/KMB intent filters in manifest.
      final Uri uri;
      if (Intent.ACTION_VIEW.equals(intent.getAction()))
        uri = intent.getData();
      else if (Intent.ACTION_SEND.equals(intent.getAction()))
        uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      else
        uri = null;
      if (uri == null)
        return null;

      MwmApplication app = MwmApplication.from(mActivity);
      final File tempDir = new File(StorageUtils.getTempPath(app));
      final ContentResolver resolver = mActivity.getContentResolver();
      ThreadPool.getStorage().execute(() -> {
        BookmarkManager.INSTANCE.importBookmarksFile(resolver, uri, tempDir);
        mActivity.runOnUiThread(mActivity::showMap);
      });
      return null;
    }
  }

  public static class ShowOnMapProcessor implements IntentProcessor
  {
    private static final String ACTION_SHOW_ON_MAP = "com.mapswithme.maps.pro.action.SHOW_ON_MAP";
    private static final String EXTRA_LAT = "lat";
    private static final String EXTRA_LON = "lon";

    @Nullable
    @Override
    public MapTask process(@NonNull Intent intent)
    {
      if (!ACTION_SHOW_ON_MAP.equals(intent.getAction()))
        return null;

      if (!intent.hasExtra(EXTRA_LAT) || !intent.hasExtra(EXTRA_LON))
        throw new AssertionError("Extra lat/lon must be provided!");

      double lat = getCoordinateFromIntent(intent, EXTRA_LAT);
      double lon = getCoordinateFromIntent(intent, EXTRA_LON);

      return new ShowPointTask(lat, lon);
    }
  }

  public static class BuildRouteProcessor implements IntentProcessor
  {
    private static final String ACTION_BUILD_ROUTE = "com.mapswithme.maps.pro.action.BUILD_ROUTE";
    private static final String EXTRA_LAT_TO = "lat_to";
    private static final String EXTRA_LON_TO = "lon_to";
    private static final String EXTRA_LAT_FROM = "lat_from";
    private static final String EXTRA_LON_FROM = "lon_from";
    private static final String EXTRA_SADDR = "saddr";
    private static final String EXTRA_DADDR = "daddr";
    private static final String EXTRA_ROUTER = "router";

    @Nullable
    @Override
    public MapTask process(@NonNull Intent intent)
    {
      if (!ACTION_BUILD_ROUTE.equals(intent.getAction()))
        return null;

      if (!intent.hasExtra(EXTRA_LAT_TO) || !intent.hasExtra(EXTRA_LON_TO))
        throw new AssertionError("Extra lat/lon must be provided!");

      String saddr = intent.getStringExtra(EXTRA_SADDR);
      String daddr = intent.getStringExtra(EXTRA_DADDR);
      double latTo = getCoordinateFromIntent(intent, EXTRA_LAT_TO);
      double lonTo = getCoordinateFromIntent(intent, EXTRA_LON_TO);
      boolean hasFrom = intent.hasExtra(EXTRA_LAT_FROM) && intent.hasExtra(EXTRA_LON_FROM);
      boolean hasRouter = intent.hasExtra(EXTRA_ROUTER);

      MapTask mapTaskToForward;
      if (hasFrom && hasRouter)
      {
        double latFrom = getCoordinateFromIntent(intent, EXTRA_LAT_FROM);
        double lonFrom = getCoordinateFromIntent(intent, EXTRA_LON_FROM);
        mapTaskToForward = new BuildRouteTask(latTo, lonTo, saddr, latFrom,lonFrom,
                                                           daddr, intent.getStringExtra(EXTRA_ROUTER));
      }
      else if (hasFrom)
      {
        double latFrom = getCoordinateFromIntent(intent, EXTRA_LAT_FROM);
        double lonFrom = getCoordinateFromIntent(intent, EXTRA_LON_FROM);
        mapTaskToForward = new BuildRouteTask(latTo, lonTo, saddr,
                                                           latFrom,lonFrom, daddr);
      }
      else
      {
        mapTaskToForward = new BuildRouteTask(latTo, lonTo,
                                                           intent.getStringExtra(EXTRA_ROUTER));
      }

      return mapTaskToForward;
    }
  }

  private static double getCoordinateFromIntent(@NonNull Intent intent, @NonNull String key)
  {
    double value = intent.getDoubleExtra(key, 0.0);
    if (Double.compare(value, 0.0) == 0)
      value = intent.getFloatExtra(key, 0.0f);

    return value;
  }

  abstract static class UrlTaskWithStatistics implements MapTask
  {
    private static final long serialVersionUID = -8661639898700431066L;
    @NonNull
    private final String mUrl;

    UrlTaskWithStatistics(@NonNull String url)
    {
      Utils.checkNotNull(url);
      mUrl = url;
    }

    @NonNull
    String getUrl()
    {
      return mUrl;
    }
  }

  public static class OpenHttpMapsUrlTask extends UrlTaskWithStatistics
  {
    OpenHttpMapsUrlTask(@NonNull String url)
    {
      super(url);
    }

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      return MapFragment.nativeShowMapForUrl(getUrl());
    }
  }

  public static class OpenUrlTask extends UrlTaskWithStatistics
  {
    private static final long serialVersionUID = -7257820771228127413L;
    private static final int SEARCH_IN_VIEWPORT_ZOOM = 16;

    OpenUrlTask(@NonNull String url)
    {
      super(url);
    }

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      final ParsingResult result = Framework.nativeParseAndSetApiUrl(getUrl());

      // TODO: Kernel recognizes "mapsme://", "mwm://" and "mapswithme://" schemas only!!!
      if (result.getUrlType() == ParsingResult.TYPE_INCORRECT)
        return MapFragment.nativeShowMapForUrl(getUrl());

      if (!result.isSuccess())
        return false;

      switch (result.getUrlType())
      {
        case ParsingResult.TYPE_INCORRECT:
        case ParsingResult.TYPE_CATALOGUE:
        case ParsingResult.TYPE_CATALOGUE_PATH:
        case ParsingResult.TYPE_SUBSCRIPTION:
          return false;

        case ParsingResult.TYPE_MAP:
          return MapFragment.nativeShowMapForUrl(getUrl());

        case ParsingResult.TYPE_ROUTE:
          final ParsedRoutingData data = Framework.nativeGetParsedRoutingData();
          RoutingController.get().setRouterType(data.mRouterType);
          final RoutePoint from = data.mPoints[0];
          final RoutePoint to = data.mPoints[1];
          RoutingController.get().prepare(MapObject.createMapObject(FeatureId.EMPTY, MapObject.API_POINT,
                                                                    from.mName, "", from.mLat, from.mLon),
                                          MapObject.createMapObject(FeatureId.EMPTY, MapObject.API_POINT,
                                                                    to.mName, "", to.mLat, to.mLon), true);
          return true;
        case ParsingResult.TYPE_SEARCH:
          final ParsedSearchRequest request = Framework.nativeGetParsedSearchRequest();
          if (request.mLat != 0.0 || request.mLon != 0.0)
          {
            Framework.nativeStopLocationFollow();
            Framework.nativeSetViewportCenter(request.mLat, request.mLon, SEARCH_IN_VIEWPORT_ZOOM,
                                              false);
            // We need to update viewport for search api manually because of drape engine
            // will not notify subscribers when search activity is shown.
            if (!request.mIsSearchOnMap)
              Framework.nativeSetSearchViewport(request.mLat, request.mLon, SEARCH_IN_VIEWPORT_ZOOM);
          }
          SearchActivity.start(target, request.mQuery, request.mLocale, request.mIsSearchOnMap);
          return true;
        case ParsingResult.TYPE_LEAD:
          return true;
      }

      return false;
    }
  }

  public static class ShowCountryTask implements MapTask {
    private static final long serialVersionUID = 256630934543189768L;
    private final String mCountryId;

    public ShowCountryTask(String countryId)
    {
      mCountryId = countryId;
    }

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      Framework.nativeShowCountry(mCountryId, false);
      return true;
    }
  }

  public static class ShowBookmarkCategoryTask implements MapTask
  {
    private static final long serialVersionUID = 8285565041410550281L;
    final long mCategoryId;

    public ShowBookmarkCategoryTask(long categoryId)
    {
      mCategoryId = categoryId;
    }

    public boolean run(@NonNull MwmActivity target)
    {
      target.showBookmarkCategoryOnMap(mCategoryId);
      return true;
    }
  }

  static abstract class BaseUserMarkTask implements MapTask
  {
    private static final long serialVersionUID = -3348320422813422144L;
    final long mCategoryId;
    final long mId;

    BaseUserMarkTask(long categoryId, long id)
    {
      mCategoryId = categoryId;
      mId = id;
    }
  }

  public static class ShowBookmarkTask extends BaseUserMarkTask
  {
    private static final long serialVersionUID = 7582931785363515736L;

    public ShowBookmarkTask(long categoryId, long bookmarkId)
    {
      super(categoryId, bookmarkId);
    }

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      target.showBookmarkOnMap(mId);
      return true;
    }
  }

  public static class ShowTrackTask extends BaseUserMarkTask
  {
    private static final long serialVersionUID = 1091286722919338991L;

    public ShowTrackTask(long categoryId, long trackId)
    {
      super(categoryId, trackId);
    }

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      target.showTrackOnMap(mId);
      return true;
    }
  }

  public static class ShowPointTask implements MapTask
  {
    private static final long serialVersionUID = -2467635346469323664L;
    private final double mLat;
    private final double mLon;

    ShowPointTask(double lat, double lon)
    {
      mLat = lat;
      mLon = lon;
    }

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      MapFragment.nativeShowMapForUrl(String.format(Locale.US,
                                                    "mapsme://map?ll=%f,%f", mLat, mLon));
      return true;
    }
  }

  public static class BuildRouteTask implements MapTask
  {
    private static final long serialVersionUID = 5301468481040195957L;
    private final double mLatTo;
    private final double mLonTo;
    @Nullable
    private final Double mLatFrom;
    @Nullable
    private final Double mLonFrom;
    @Nullable
    private final String mSaddr;
    @Nullable
    private final String mDaddr;
    private final String mRouter;

    @NonNull
    private static MapObject fromLatLon(double lat, double lon, @Nullable String addr)
    {
      return MapObject.createMapObject(FeatureId.EMPTY, MapObject.API_POINT,
                                       TextUtils.isEmpty(addr) ? "" : addr, "", lat, lon);
    }

    BuildRouteTask(double latTo, double lonTo, @Nullable String router)
    {
      this(latTo, lonTo, null, null, null, null, router);
    }

    BuildRouteTask(double latTo, double lonTo, @Nullable String saddr,
                   @Nullable Double latFrom, @Nullable Double lonFrom, @Nullable String daddr)
    {
      this(latTo, lonTo, saddr, latFrom, lonFrom, daddr, null);
    }

    BuildRouteTask(double latTo, double lonTo, @Nullable String saddr,
                   @Nullable Double latFrom, @Nullable Double lonFrom, @Nullable String daddr,
                   @Nullable String router)
    {
      mLatTo = latTo;
      mLonTo = lonTo;
      mLatFrom = latFrom;
      mLonFrom = lonFrom;
      mSaddr = saddr;
      mDaddr = daddr;
      mRouter = router;
    }

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      @Framework.RouterType int routerType = -1;
      if (!TextUtils.isEmpty(mRouter))
      {
        switch (mRouter)
        {
          case "vehicle":
            routerType = Framework.ROUTER_TYPE_VEHICLE;
            break;
          case "pedestrian":
            routerType = Framework.ROUTER_TYPE_PEDESTRIAN;
            break;
          case "bicycle":
            routerType = Framework.ROUTER_TYPE_BICYCLE;
            break;
          case "transit":
            routerType = Framework.ROUTER_TYPE_TRANSIT;
            break;
        }
      }

      if (mLatFrom != null && mLonFrom != null && routerType >= 0)
      {
        RoutingController.get().prepare(fromLatLon(mLatFrom, mLonFrom, mSaddr),
                                        fromLatLon(mLatTo, mLonTo, mDaddr), routerType,
                                        true /* fromApi */);
      }
      else if (mLatFrom != null && mLonFrom != null)
      {
        RoutingController.get().prepare(fromLatLon(mLatFrom, mLonFrom, mSaddr),
                                        fromLatLon(mLatTo, mLonTo, mDaddr), true /* fromApi */);
      }
      else if (routerType > 0)
      {
        MapObject startPoint = LocationHelper.INSTANCE.getMyPosition();
        RoutingController.get().prepare(startPoint,
                                        fromLatLon(mLatTo, mLonTo, mDaddr), routerType,
                                        true /* fromApi */);
      }
      else
      {
        MapObject startPoint = LocationHelper.INSTANCE.getMyPosition();
        RoutingController.get().prepare(startPoint,
                                        fromLatLon(mLatTo, mLonTo, mDaddr), true /* fromApi */);
      }
      return true;
    }
  }

  public static class RestoreRouteTask implements MapTask
  {
    private static final long serialVersionUID = 6123893958975977040L;

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      RoutingController.get().restoreRoute();
      return true;
    }
  }

  public static class ShowDialogTask implements MapTask
  {
    private static final long serialVersionUID = 1548931513812565018L;
    @NonNull
    private final String mDialogName;

    public ShowDialogTask(@NonNull String dialogName)
    {
      mDialogName = dialogName;
    }

    @Override
    public boolean run(@NonNull MwmActivity target)
    {
      Fragment f = target.getSupportFragmentManager().findFragmentByTag(mDialogName);
      if (f != null)
        return true;

      final DialogFragment fragment = (DialogFragment) Fragment.instantiate(target, mDialogName);
      fragment.show(target.getSupportFragmentManager(), mDialogName);
      return true;
    }

    @NonNull
    private static Bundle toDialogArgs(@NonNull List<KeyValue> pairs)
    {
      Bundle bundle = new Bundle();
      for (KeyValue each : pairs)
        bundle.putString(each.getKey(), each.getValue());
      return bundle;
    }
  }
}
