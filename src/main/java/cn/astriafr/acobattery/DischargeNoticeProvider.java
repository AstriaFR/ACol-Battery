package cn.astriafr.acobattery;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import cn.astriafr.acobattery.common.DischargeNotice;

/** Private endpoint for the root recorder to deliver one recorded notification. */
public final class DischargeNoticeProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}

    @Override public Bundle call(String method,String arg,Bundle extras){
        if(Binder.getCallingUid()!=0)throw new SecurityException("root required");
        if(!DischargeNotice.ACTION.equals(method))throw new IllegalArgumentException("Unsupported notification request");
        long caller=Binder.clearCallingIdentity();
        try{
            Bundle result=new Bundle();
            result.putBoolean("posted",DischargeNotificationReceiver.deliver(getContext(),extras));
            return result;
        }finally{Binder.restoreCallingIdentity(caller);}
    }

    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sortOrder){throw new UnsupportedOperationException();}
    @Override public String getType(Uri uri){throw new UnsupportedOperationException();}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
